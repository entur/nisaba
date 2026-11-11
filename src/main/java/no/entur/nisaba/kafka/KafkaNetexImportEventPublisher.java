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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static no.entur.nisaba.kafka.IdempotentTopicContract.CLIENT_ID_EVENT_PRODUCER;

/**
 * The Kafka producer of NeTEx import events, with the wire format of the Camel route:
 * key = codespace, value = Avro {@link NetexImportEvent} through the Confluent serializer, and exactly one
 * record header, {@code EnturDatasetImportKey}.
 */
public class KafkaNetexImportEventPublisher implements NetexImportEventPublisher, SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaNetexImportEventPublisher.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String topic;
    private final Map<String, Object> producerConfig;
    private final Duration sendTimeout;
    private volatile Producer<String, NetexImportEvent> producer;

    public KafkaNetexImportEventPublisher(String topic, Map<String, Object> producerConfig, Duration sendTimeout) {
        this.topic = topic;
        this.producerConfig = producerConfig;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(NetexImportEvent event) {
        Producer<String, NetexImportEvent> p = producer;
        if (p == null) {
            throw new IllegalStateException("The Kafka event publisher is not running");
        }
        ProducerRecord<String, NetexImportEvent> record = record(topic, event);
        try {
            // the producer's delivery timeout equals the send timeout, so the future completes within it
            RecordMetadata metadata = p.send(record).get(sendTimeout.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            LOGGER.debug("Published event {} to {} partition {} offset {}", event.getImportKey(), topic,
                    metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing event " + event.getImportKey(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to publish event " + event.getImportKey() + " to " + topic, e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Publishing event " + event.getImportKey() + " to " + topic
                    + " did not complete within " + sendTimeout, e);
        }
    }

    static ProducerRecord<String, NetexImportEvent> record(String topic, NetexImportEvent event) {
        return new ProducerRecord<>(topic, null, event.getCodespace().toString(), event,
                List.of(new RecordHeader(Constants.DATASET_IMPORT_KEY,
                        event.getImportKey().toString().getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * The producer gives up on a record within the send timeout: a publish that throws has not written the
     * event, so the retry that follows cannot produce a second one.
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
            producer = new KafkaProducer<>(producerProperties(producerConfig, sendTimeout));
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
