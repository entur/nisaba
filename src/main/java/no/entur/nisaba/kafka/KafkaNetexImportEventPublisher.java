/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package no.entur.nisaba.kafka;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import no.entur.nisaba.Constants;
import no.entur.nisaba.avro.NetexImportEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static no.entur.nisaba.kafka.IdempotentTopicContract.CLIENT_ID_EVENT_PRODUCER;

/**
 * The Kafka producer of NeTEx import events, with the wire format of the Camel route:
 * key = codespace, value = Avro {@link NetexImportEvent} through the Confluent serializer, and exactly one
 * record header, {@code EnturDatasetImportKey}.
 * <p>
 * A send that fails may still have written the event: the delivery timeout can expire, or the wait be
 * interrupted, after the broker appended the record. Before reporting the failure, which makes the caller
 * release its claim and a retry publish again, the publisher waits for the reader of the topic to see the
 * event; if it does, the publish succeeded. What remains is an event that lands after that wait, or a reader
 * that is behind: then the event is published twice, never lost.
 */
public class KafkaNetexImportEventPublisher implements NetexImportEventPublisher, SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaNetexImportEventPublisher.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String topic;
    private final Supplier<Producer<String, NetexImportEvent>> producerFactory;
    private final Duration sendTimeout;
    private final PublishedImportKeys publishedImportKeys;
    private final Duration verificationTimeout;
    private volatile Producer<String, NetexImportEvent> producer;

    /**
     * @param publishedImportKeys what the reader of the event topic has seen.
     * @param verificationTimeout how long to wait for the reader to see an event whose send failed.
     */
    public KafkaNetexImportEventPublisher(String topic, Map<String, Object> producerConfig, Duration sendTimeout,
                                          PublishedImportKeys publishedImportKeys, Duration verificationTimeout) {
        this(topic, () -> new KafkaProducer<>(producerProperties(producerConfig, sendTimeout)), sendTimeout,
                publishedImportKeys, verificationTimeout);
    }

    KafkaNetexImportEventPublisher(String topic, Supplier<Producer<String, NetexImportEvent>> producerFactory, Duration sendTimeout,
                                   PublishedImportKeys publishedImportKeys, Duration verificationTimeout) {
        this.topic = topic;
        this.producerFactory = producerFactory;
        this.sendTimeout = sendTimeout;
        this.publishedImportKeys = publishedImportKeys;
        this.verificationTimeout = verificationTimeout;
    }

    @Override
    public void publish(NetexImportEvent event) {
        Producer<String, NetexImportEvent> p = producer;
        if (p == null) {
            throw new IllegalStateException("The Kafka event publisher is not running");
        }
        String importKey = event.getImportKey().toString();
        ProducerRecord<String, NetexImportEvent> record = record(topic, event);
        long offsetBefore = publishedImportKeys.lastOffset(importKey);
        // a send that throws here (serialization, no metadata) has not left the producer
        Future<RecordMetadata> sent = p.send(record);
        IllegalStateException failure;
        try {
            // the producer's delivery timeout equals the send timeout, so the future completes within it
            RecordMetadata metadata = sent.get(sendTimeout.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            LOGGER.debug("Published event {} to {} partition {} offset {}", importKey, topic,
                    metadata.partition(), metadata.offset());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing event " + importKey, e);
        } catch (ExecutionException e) {
            failure = new IllegalStateException("Failed to publish event " + importKey + " to " + topic, e.getCause());
        } catch (TimeoutException e) {
            failure = new IllegalStateException("Publishing event " + importKey + " to " + topic
                    + " did not complete within " + sendTimeout, e);
        }
        if (publishedImportKeys.awaitBeyond(importKey, offsetBefore, verificationTimeout)) {
            LOGGER.warn("The send of event {} to {} failed ({}) but the event was read back from the topic at offset {}: "
                    + "it is published", importKey, topic, String.valueOf(failure.getCause()), publishedImportKeys.lastOffset(importKey));
            return;
        }
        throw failure;
    }

    static ProducerRecord<String, NetexImportEvent> record(String topic, NetexImportEvent event) {
        return new ProducerRecord<>(topic, null, event.getCodespace().toString(), event,
                List.of(new RecordHeader(Constants.DATASET_IMPORT_KEY,
                        event.getImportKey().toString().getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * The producer gives up on a record within the send timeout, so that {@link #publish} can tell whether
     * the event was written before the caller retries.
     */
    static Map<String, Object> producerProperties(Map<String, Object> config, Duration sendTimeout) {
        Map<String, Object> props = new HashMap<>(config);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, CLIENT_ID_EVENT_PRODUCER);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        forceDurable(props);
        long sendMillis = sendTimeout.toMillis();
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) sendMillis);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.max(1000, sendMillis / 2));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) Math.min(10_000, sendMillis));
        return props;
    }

    /**
     * A publish that returns must have written the event: an acknowledged record must be durable, whatever
     * {@code spring.kafka.producer.*} says.
     */
    static void forceDurable(Map<String, Object> props) {
        Object acks = props.put(ProducerConfig.ACKS_CONFIG, "all");
        Object idempotence = props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        if (acks != null && !"all".equals(acks.toString()) && !"-1".equals(acks.toString())) {
            LOGGER.warn("Overriding producer acks={} with acks=all", acks);
        }
        if (idempotence != null && !"true".equals(idempotence.toString())) {
            LOGGER.warn("Overriding producer enable.idempotence={} with true", idempotence);
        }
    }

    @Override
    public void start() {
        if (producer == null) {
            producer = producerFactory.get();
            LOGGER.info("Started the Kafka event publisher for topic {}", topic);
        }
    }

    @Override
    public void stop() {
        Producer<String, NetexImportEvent> p = producer;
        producer = null;
        if (p != null) {
            p.close(CLOSE_TIMEOUT);
            LOGGER.info("Stopped the Kafka event publisher for topic {}", topic);
        }
    }

    @Override
    public boolean isRunning() {
        return producer != null;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
