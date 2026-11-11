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

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.pipeline.ImportMdc;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static no.entur.nisaba.kafka.IdempotentTopicContract.CLIENT_ID_EVENT_READER;

/**
 * Keeps the import-date map behind the public REST API up to date from the event topic.
 * <p>
 * Replaces the Camel {@code from("kafka:...?seekTo=beginning")} route: every pod reads the whole topic from the
 * beginning at start-up (the topic is compacted, so that is one event per codespace) and keeps following it,
 * with no consumer group. The map is populated before the application reports ready.
 * <p>
 * It also tells {@link PublishedImportKeys} which import keys it has seen, for the publisher.
 */
public class ImportDateMapReader implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportDateMapReader.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String topic;
    private final Map<String, Object> consumerConfig;
    private final Map<String, String> importDates;
    private final PublishedImportKeys publishedImportKeys;
    private final Duration pollTimeout;
    private final Duration replayTimeout;
    private volatile AssignedTopicPoller<String, NetexImportEvent> poller;

    public ImportDateMapReader(String topic, Map<String, Object> consumerConfig, Map<String, String> importDates,
                               PublishedImportKeys publishedImportKeys, Duration pollTimeout, Duration replayTimeout) {
        this.topic = topic;
        this.consumerConfig = consumerConfig;
        this.importDates = importDates;
        this.publishedImportKeys = publishedImportKeys;
        this.pollTimeout = pollTimeout;
        this.replayTimeout = replayTimeout;
    }

    void onEvent(ConsumerRecord<String, NetexImportEvent> record) {
        NetexImportEvent event = record.value();
        if (event == null) {
            return;
        }
        publishedImportKeys.seen(event.getImportKey().toString(), record.offset());
        String codespace = event.getCodespace().toString();
        LOGGER.info("{}Received notification event from {}", ImportMdc.prefix(codespace, null), topic);
        LocalDateTime importDateTime = LocalDateTime.parse(event.getImportDateTime().toString());
        importDates.put(codespace, importDateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        LOGGER.debug("Registered import dates: {}", importDates);
    }

    static Map<String, Object> consumerProperties(Map<String, Object> config) {
        Map<String, Object> props = AssignedTopicPoller.consumerProperties(config, CLIENT_ID_EVENT_READER,
                StringDeserializer.class, KafkaAvroDeserializer.class);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true");
        return props;
    }

    @Override
    public void start() {
        if (poller != null) {
            return;
        }
        AssignedTopicPoller<String, NetexImportEvent> p = new AssignedTopicPoller<>("import-date-map", topic,
                () -> new KafkaConsumer<>(consumerProperties(consumerConfig)), this::onEvent, pollTimeout, replayTimeout);
        p.start();
        poller = p;
        LOGGER.info("Import dates loaded from topic {}: {} codespaces", topic, importDates.size());
    }

    @Override
    public void stop() {
        AssignedTopicPoller<String, NetexImportEvent> p = poller;
        poller = null;
        if (p != null) {
            p.stop(CLOSE_TIMEOUT);
        }
    }

    @Override
    public boolean isRunning() {
        return poller != null;
    }

    public boolean isAlive() {
        AssignedTopicPoller<String, NetexImportEvent> p = poller;
        return p != null && p.isAlive();
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
