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

package no.entur.nisaba.idempotent.migration;

import no.entur.nisaba.idempotent.IdempotentRepositoryException;
import no.entur.nisaba.idempotent.InMemoryMarkerStore;
import no.entur.nisaba.idempotent.MarkerStore;
import no.entur.nisaba.idempotent.migration.IdempotentRepositoryMigration.Result;
import no.entur.nisaba.kafka.EmbeddedKafkaExtension;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration against a real topic holding what both previous versions wrote.
 */
class IdempotentRepositoryMigrationTest {

    @RegisterExtension
    static final EmbeddedKafkaExtension KAFKA = new EmbeddedKafkaExtension();

    private final InMemoryMarkerStore store = new InMemoryMarkerStore();

    private static String topic(TestInfo info, int partitions) {
        String topic = "idempotent-" + info.getTestMethod().orElseThrow().getName();
        KAFKA.createTopic(topic, partitions);
        return topic;
    }

    /**
     * camel1 and camel2 by the Camel version (no header), camel2 removed; kafka1 claimed and kept, kafka2
     * claimed and released, kafka3 released then claimed again, by the Kafka claim repository.
     */
    private static void writeHistory(String topic) throws Exception {
        try (KafkaProducer<String, String> producer = KAFKA.rawProducer()) {
            send(producer, topic, "camel1", "add", null);
            send(producer, topic, "camel2", "add", null);
            send(producer, topic, "camel2", "remove", null);
            send(producer, topic, "kafka1", "add", "pod-a:1");
            send(producer, topic, "kafka1", "add", "pod-b:2");
            send(producer, topic, "kafka2", "add", "pod-a:3");
            send(producer, topic, "kafka2", "remove", "pod-a:3");
            send(producer, topic, "kafka3", "add", "pod-a:4");
            send(producer, topic, "kafka3", "remove", "pod-a:4");
            send(producer, topic, "kafka3", "add", "pod-b:5");
            send(producer, topic, "camel1", "bogus", null);
        }
    }

    private static void send(KafkaProducer<String, String> producer, String topic, String key, String value, String claimId) throws Exception {
        List<org.apache.kafka.common.header.Header> headers = claimId == null ? List.of()
                : List.of(new RecordHeader("nisaba-claim-id", claimId.getBytes(StandardCharsets.UTF_8)));
        producer.send(new ProducerRecord<>(topic, null, key, value, headers)).get();
    }

    private IdempotentRepositoryMigration migration(String topic, MarkerStore target, boolean dryRun) {
        return new IdempotentRepositoryMigration(topic, KAFKA.clientProperties(), target, dryRun, 4,
                Duration.ofMillis(200), Duration.ofSeconds(30));
    }

    @Test
    void presentKeysBecomeConfirmedMarkers(TestInfo info) throws Exception {
        String topic = topic(info, 3);
        writeHistory(topic);

        Result result = migration(topic, store, false).run();

        assertEquals(new Result(3, 3, 0, 0), result);
        assertTrue(result.successful());
        assertEquals(3, store.size());
        assertEquals("migrated:camel", store.read("camel1").orElseThrow().claimId());
        assertEquals("migrated:pod-a:1", store.read("kafka1").orElseThrow().claimId());
        assertEquals("migrated:pod-b:5", store.read("kafka3").orElseThrow().claimId());
        assertTrue(store.read("camel1").orElseThrow().confirmed());
        assertTrue(store.read("kafka1").orElseThrow().confirmed());
        assertTrue(store.read("kafka3").orElseThrow().confirmed());
        assertFalse(store.contains("camel2"));
        assertFalse(store.contains("kafka2"));
    }

    @Test
    void secondRunOnlyCreatesWhatIsMissingAndOverwritesNothing(TestInfo info) throws Exception {
        String topic = topic(info, 1);
        writeHistory(topic);
        // a key the new version claimed itself, and is publishing right now
        long live = store.createIfAbsent("kafka1", "pod-c:9", false).orElseThrow();

        assertEquals(new Result(3, 2, 1, 0), migration(topic, store, false).run());
        assertEquals("pod-c:9", store.read("kafka1").orElseThrow().claimId());
        assertEquals(live, store.read("kafka1").orElseThrow().generation());
        assertFalse(store.read("kafka1").orElseThrow().confirmed());

        // the previous version published one more dataset during the rolling update
        try (KafkaProducer<String, String> producer = KAFKA.rawProducer()) {
            send(producer, topic, "late", "add", "pod-a:6");
        }
        assertEquals(new Result(4, 1, 3, 0), migration(topic, store, false).run());
        assertTrue(store.contains("late"));
    }

    @Test
    void dryRunWritesNothingAndCountsWhatItWouldCreate(TestInfo info) throws Exception {
        String topic = topic(info, 1);
        writeHistory(topic);
        store.createIfAbsent("camel1", "migrated:camel", true);

        assertEquals(new Result(3, 2, 1, 0), migration(topic, store, true).run());
        assertEquals(1, store.size());
    }

    @Test
    void emptyTopicMigratesNothing(TestInfo info) {
        assertEquals(new Result(0, 0, 0, 0), migration(topic(info, 1), store, false).run());
    }

    @Test
    void failedKeysAreCountedAndTheOthersMigrated(TestInfo info) throws Exception {
        String topic = topic(info, 1);
        writeHistory(topic);
        MarkerStore failing = new MarkerStore() {
            @Override
            public OptionalLong createIfAbsent(String name, String claimId, boolean confirmed) {
                if (name.equals("kafka1")) {
                    throw new IdempotentRepositoryException("no access");
                }
                return store.createIfAbsent(name, claimId, confirmed);
            }

            @Override
            public Optional<Marker> read(String name) {
                return store.read(name);
            }

            @Override
            public boolean confirm(String name, long generation) {
                return store.confirm(name, generation);
            }

            @Override
            public boolean deleteIfGeneration(String name, long generation) {
                return store.deleteIfGeneration(name, generation);
            }

            @Override
            public void checkAccess() {
            }

            @Override
            public String location(String name) {
                return store.location(name);
            }
        };

        Result result = migration(topic, failing, false).run();

        assertEquals(new Result(3, 2, 0, 1), result);
        assertFalse(result.successful());
    }

    @Test
    void commandLineToolStartsOnlyWhatItNeedsAndReportsTheOutcome(TestInfo info) throws Exception {
        String topic = topic(info, 1);
        writeHistory(topic);
        // outside the gcs-blobstore profile the tool writes to a marker store of its own, in memory
        assertEquals(0, IdempotentRepositoryMigrationApp.run(
                "--spring.kafka.bootstrap-servers=" + KAFKA.bootstrapServers(),
                "--nisaba.kafka.topic.idempotent=" + topic,
                "--nisaba.kafka.poll-timeout=200ms",
                "--nisaba.idempotent.migration.threads=2"));
        assertThrows(IllegalStateException.class, () -> IdempotentRepositoryMigrationApp.run(
                "--spring.kafka.bootstrap-servers=" + KAFKA.bootstrapServers(),
                "--nisaba.kafka.topic.idempotent=no-such-topic",
                "--nisaba.kafka.replay-timeout=10s"));
    }

    @Test
    void missingTopicFailsTheMigration() {
        IdempotentRepositoryMigration migration = migration("no-such-topic", store, false);
        assertThrows(IllegalStateException.class, migration::run);
    }
}
