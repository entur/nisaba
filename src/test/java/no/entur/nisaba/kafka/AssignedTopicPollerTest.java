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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record that cannot be deserialized is skipped only when the record is at fault, never when the schema
 * registry is.
 */
class AssignedTopicPollerTest {

    private static final String TOPIC = "events";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final List<String> handled = new CopyOnWriteArrayList<>();

    private static RecordDeserializationException failure(long offset, RuntimeException cause) {
        return new RecordDeserializationException(RecordDeserializationException.DeserializationExceptionOrigin.VALUE,
                PARTITION, offset, 0, TimestampType.CREATE_TIME, null, null, null, "Error deserializing value", cause);
    }

    /** A topic of three records, to be replayed. */
    private static MockConsumer<String, String> consumer() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, node, new Node[]{node}, new Node[]{node})));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        consumer.updateEndOffsets(Map.of(PARTITION, 3L));
        return consumer;
    }

    private AssignedTopicPoller<String, String> poller(MockConsumer<String, String> consumer, Duration replayTimeout) {
        return new AssignedTopicPoller<>("test", TOPIC, () -> consumer, record -> handled.add(record.value()),
                Duration.ofMillis(10), replayTimeout);
    }

    private static void addRecords(MockConsumer<String, String> consumer, long from) {
        for (long offset = from; offset < 3; offset++) {
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, offset, "k" + offset, "v" + offset));
        }
    }

    @Test
    void recordAtFaultIsSkipped() {
        MockConsumer<String, String> consumer = consumer();
        consumer.schedulePollTask(() -> consumer.setPollException(failure(0, new SerializationException("Unknown magic byte!"))));
        consumer.schedulePollTask(() -> {
            assertEquals(1, consumer.position(PARTITION), "the poller moved past the record");
            addRecords(consumer, 1);
        });
        AssignedTopicPoller<String, String> poller = poller(consumer, Duration.ofSeconds(10));
        poller.start();
        poller.stop(Duration.ofSeconds(5));
        assertEquals(List.of("v1", "v2"), handled);
    }

    @Test
    void recordIsReadAgainWhenTheSchemaRegistryFails() {
        MockConsumer<String, String> consumer = consumer();
        consumer.schedulePollTask(() -> consumer.setPollException(failure(0, new DisconnectException("502"))));
        consumer.schedulePollTask(() -> consumer.setPollException(failure(0, new DisconnectException("502"))));
        consumer.schedulePollTask(() -> {
            assertEquals(0, consumer.position(PARTITION), "the poller stayed on the record");
            addRecords(consumer, 0);
        });
        AssignedTopicPoller<String, String> poller = poller(consumer, Duration.ofSeconds(10));
        poller.start();
        poller.stop(Duration.ofSeconds(5));
        assertEquals(List.of("v0", "v1", "v2"), handled, "nothing was dropped");
    }

    @Test
    void replayFailsWhenTheSchemaRegistryStaysDown() {
        MockConsumer<String, String> consumer = consumer();
        for (int i = 0; i < 1000; i++) {
            consumer.schedulePollTask(() -> consumer.setPollException(failure(0, new DisconnectException("502"))));
        }
        AssignedTopicPoller<String, String> poller = poller(consumer, Duration.ofMillis(300));
        IllegalStateException e = assertThrows(IllegalStateException.class, poller::start);
        assertTrue(e.getMessage().contains("did not complete within") && e.getMessage().contains("DisconnectException"), e.getMessage());
        assertTrue(handled.isEmpty());
        assertFalse(poller.isAlive());
    }

    /**
     * Pins how the Confluent deserializer reports an unreachable registry, which the poller tells apart from
     * a bad record by the exception.
     */
    @Test
    void confluentDeserializerFailuresAreToldApart() {
        try (KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer()) {
            deserializer.configure(Map.of("schema.registry.url", "http://localhost:1"), false);
            byte[] unknownMagicByte = "not avro".getBytes();
            byte[] schemaToFetch = ByteBuffer.allocate(6).put((byte) 0).putInt(42).put((byte) 0).array();

            RuntimeException badRecord = assertThrows(RuntimeException.class, () -> deserializer.deserialize(TOPIC, unknownMagicByte));
            assertTrue(AssignedTopicPoller.isRecordAtFault(failure(0, badRecord)), badRecord.toString());

            RuntimeException registryDown = assertThrows(RuntimeException.class, () -> deserializer.deserialize(TOPIC, schemaToFetch));
            assertFalse(AssignedTopicPoller.isRecordAtFault(failure(0, registryDown)), registryDown + " caused by " + registryDown.getCause());
        }
    }
}
