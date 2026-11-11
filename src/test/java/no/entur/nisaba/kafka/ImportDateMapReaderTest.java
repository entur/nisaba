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

import no.entur.nisaba.avro.NetexImportEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportDateMapReaderTest {

    @RegisterExtension
    static final EmbeddedKafkaExtension KAFKA = new EmbeddedKafkaExtension();

    private static final String REGISTRY = "mock://nisaba";

    @org.junit.jupiter.api.BeforeAll
    static void trustAvroClasses() {
        new no.entur.nisaba.config.AvroClassSecurityConfig().trustNisabaAvroClasses();
    }

    static NetexImportEvent event(String codespace, LocalDateTime importDateTime) {
        return NetexImportEvent.newBuilder()
                .setCodespace(codespace)
                .setImportDateTime(importDateTime.toString())
                .setImportKey(codespace + "_" + importDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace(':', '_'))
                .setPublishedDatasetURI("gs://marduk/outbound/netex/rb_" + codespace + "-aggregated-netex.zip")
                .setPublishedDatasetPublicLink("https://storage.googleapis.com/marduk/outbound/netex/rb_" + codespace + "-aggregated-netex.zip")
                .setOriginalDatasetURI("")
                .setServiceJourneys(0)
                .setCommonFiles(0)
                .build();
    }

    @Test
    void replaysEveryEventAndSkipsAPoisonRecord() throws Exception {
        String topic = "rutedata-dataset-import-event-test";
        KAFKA.createTopic(topic, 1);
        Map<String, Object> props = KAFKA.clientProperties();
        props.put("schema.registry.url", REGISTRY);
        KafkaNetexImportEventPublisher publisher = new KafkaNetexImportEventPublisher(topic, props, Duration.ofSeconds(10), new PublishedImportKeys(), Duration.ZERO);
        publisher.start();
        try {
            publisher.publish(event("opp", LocalDateTime.of(2021, 8, 1, 10, 0, 0, 123_000_000)));
            publisher.publish(event("flt", LocalDateTime.of(2021, 8, 2, 11, 0, 0)));
            publisher.publish(event("vyg", LocalDateTime.of(2021, 8, 3, 12, 0, 0)));
        } finally {
            publisher.stop();
        }
        Map<String, Object> raw = KAFKA.clientProperties();
        raw.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        raw.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<String, byte[]> poison = new KafkaProducer<>(raw)) {
            poison.send(new ProducerRecord<>(topic, "bad", "not avro".getBytes(StandardCharsets.UTF_8))).get();
        }
        publisher.start();
        try {
            publisher.publish(event("opp", LocalDateTime.of(2021, 8, 4, 10, 0, 0)));
        } finally {
            publisher.stop();
        }

        Map<String, String> importDates = new ConcurrentHashMap<>();
        PublishedImportKeys publishedImportKeys = new PublishedImportKeys();
        ImportDateMapReader reader = new ImportDateMapReader(topic, props, importDates, publishedImportKeys, Duration.ofMillis(100), Duration.ofSeconds(30));
        reader.start();
        try {
            assertEquals(Map.of("opp", "2021-08-04T10:00:00", "flt", "2021-08-02T11:00:00", "vyg", "2021-08-03T12:00:00"), importDates);
            assertTrue(reader.isAlive(), "the poison record did not kill the reader");
            assertEquals(1, publishedImportKeys.lastOffset("flt_2021-08-02T11_00_00"));
            assertEquals(4, publishedImportKeys.lastOffset("opp_2021-08-04T10_00_00"));
            assertEquals(PublishedImportKeys.NEVER, publishedImportKeys.lastOffset("opp_2021-08-05T10_00_00"));
        } finally {
            reader.stop();
        }
        assertTrue(!reader.isRunning());
    }

    @Test
    void publisherWritesTheCamelWireFormat() {
        String topic = "rutedata-dataset-import-event-wire";
        KAFKA.createTopic(topic, 1);
        Map<String, Object> props = KAFKA.clientProperties();
        props.put("schema.registry.url", REGISTRY);
        KafkaNetexImportEventPublisher publisher = new KafkaNetexImportEventPublisher(topic, props, Duration.ofSeconds(10), new PublishedImportKeys(), Duration.ZERO);
        publisher.start();
        try {
            publisher.publish(event("avi", LocalDateTime.of(2021, 4, 21, 11, 51, 59)));
        } finally {
            publisher.stop();
        }
        List<ConsumerRecord<byte[], byte[]>> records = KAFKA.readAll(topic);
        assertEquals(1, records.size());
        ConsumerRecord<byte[], byte[]> record = records.getFirst();
        assertEquals("avi", new String(record.key(), StandardCharsets.UTF_8));
        assertEquals(1, record.headers().toArray().length);
        assertEquals("avi_2021-04-21T11_51_59", new String(record.headers().lastHeader("EnturDatasetImportKey").value(), StandardCharsets.UTF_8));
        assertEquals(0, record.value()[0], "Confluent wire format magic byte");
        Map<String, Object> producerProps = KafkaNetexImportEventPublisher.producerProperties(props, Duration.ofSeconds(30));
        assertEquals("nisaba-event", producerProps.get(ProducerConfig.CLIENT_ID_CONFIG));
        assertEquals(30_000, producerProps.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG), "the producer gives up within the send timeout");
    }
}
