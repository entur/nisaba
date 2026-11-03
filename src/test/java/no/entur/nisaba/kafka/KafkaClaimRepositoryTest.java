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

import no.entur.nisaba.kafka.ImportEventClaimRepository.Claim;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim protocol against an embedded broker. Every test owns a topic; nothing depends on thread timing:
 * the interleavings are constructed with the poller's pause/resume hook and the failable producer.
 */
class KafkaClaimRepositoryTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaClaimRepositoryTest.class);

    @RegisterExtension
    static final EmbeddedKafkaExtension KAFKA = new EmbeddedKafkaExtension();

    private static final Duration SHORT = Duration.ofMillis(500);
    private static final KafkaClaimRepository.Timeouts TIMEOUTS =
            new KafkaClaimRepository.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMillis(100));
    private static final KafkaClaimRepository.Timeouts SHORT_CLAIM =
            new KafkaClaimRepository.Timeouts(Duration.ofSeconds(10), SHORT, Duration.ofSeconds(30), Duration.ofMillis(100));
    /** A short send timeout: the grace before an add the producer gave up on is forgotten is the send timeout. */
    private static final KafkaClaimRepository.Timeouts SHORT_SEND =
            new KafkaClaimRepository.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMillis(100));

    private final List<KafkaClaimRepository> started = new ArrayList<>();
    private String topic;

    /**
     * The invariant every scenario must end with: nothing left in the local registry and no future waiting for
     * a record, before and after stop.
     */
    @AfterEach
    void nothingLeftBehindThenStopAll() {
        for (KafkaClaimRepository repository : started) {
            if (repository.isRunning()) {
                await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertEquals(0, repository.registrySize(), "registry entries left");
                    assertEquals(0, repository.pendingFutures(), "pending futures left");
                });
            }
            repository.stop();
            assertEquals(0, repository.registrySize());
            assertEquals(0, repository.pendingFutures());
        }
    }

    private String topic(TestInfo info) {
        topic = "idempotent-" + info.getTestMethod().orElseThrow().getName();
        KAFKA.createTopic(topic, 1);
        return topic;
    }

    private KafkaClaimRepository repository(String instanceId, KafkaClaimRepository.Timeouts timeouts) {
        return repository(instanceId, timeouts, config -> KafkaClaimRepository.createProducer(config, timeouts));
    }

    private KafkaClaimRepository repository(String instanceId, KafkaClaimRepository.Timeouts timeouts,
                                            java.util.function.Function<Map<String, Object>, org.apache.kafka.clients.producer.Producer<String, String>> producerFactory) {
        Map<String, Object> producerProps = KAFKA.clientProperties();
        producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, "nisaba-idempotent-repo-" + instanceId);
        Map<String, Object> consumerProps = KAFKA.clientProperties();
        consumerProps.put(ProducerConfig.CLIENT_ID_CONFIG, "nisaba-idempotent-repo-" + instanceId);
        KafkaClaimRepository repository = new KafkaClaimRepository(topic, producerProps, consumerProps, instanceId, timeouts, producerFactory);
        repository.start();
        started.add(repository);
        return repository;
    }

    private static String value(ConsumerRecord<byte[], byte[]> record) {
        return record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8);
    }

    private static String key(ConsumerRecord<byte[], byte[]> record) {
        return record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8);
    }

    private List<String> values() {
        return KAFKA.readAll(topic).stream().map(KafkaClaimRepositoryTest::value).toList();
    }

    // (a)
    @Test
    void replaysHistoryWrittenByTheCamelVersion(TestInfo info) throws Exception {
        topic(info);
        try (KafkaProducer<String, String> camel = KAFKA.rawProducer()) {
            for (String[] r : new String[][]{{"k1", "add"}, {"k2", "add"}, {"k2", "remove"}, {"k3", "add"},
                    {null, "clear"}, {"k4", "add"}, {"k4", "add"}, {"k5", "bogus"}}) {
                camel.send(new ProducerRecord<>(topic, r[0], r[1])).get();
            }
        }
        KafkaClaimRepository repository = repository("a", TIMEOUTS);
        assertEquals(1, repository.size());
        assertTrue(repository.contains("k4"));
        assertEquals(Optional.of(IdempotentTopicState.UNKNOWN_OWNER), repository.owner("k4"));

        long before = KAFKA.recordCount(topic);
        assertTrue(repository.claim("k4").isEmpty(), "a key written by Camel is a duplicate");
        assertEquals(before, KAFKA.recordCount(topic), "the fast path writes nothing");

        Optional<Claim> k1 = repository.claim("k1");
        assertTrue(k1.isPresent(), "a cleared key is new again");
        repository.confirm(k1.get());
        assertEquals(0, repository.registrySize());
    }

    // (b) Mutation check: make claim() return the token right after send().get() (Camel semantics) and this test fails,
    // because B then publishes too.
    @Test
    void twoPodsRacingForOneKeyYieldExactlyOneOwner(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        KafkaClaimRepository b = repository("b", TIMEOUTS);
        b.poller().pause();

        Optional<Claim> claimA = a.claim("k");
        assertTrue(claimA.isPresent());
        assertFalse(b.contains("k"), "B has not seen A's add");

        CompletableFuture<Optional<Claim>> claimB = CompletableFuture.supplyAsync(() -> b.claim("k"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> KAFKA.recordCount(topic) == 2);
        assertFalse(claimB.isDone(), "B waits for its own record");
        b.poller().resume();

        assertTrue(claimB.get(10, TimeUnit.SECONDS).isEmpty(), "B lost");
        assertEquals(Optional.of(claimA.get().claimId()), b.owner("k"));
        assertEquals(List.of("add", "add"), values());
        a.confirm(claimA.get());
        assertEquals(0, a.registrySize());
        assertEquals(0, b.registrySize());
    }

    // (c)
    @Test
    void releaseMakesTheKeyClaimableAgain(TestInfo info) {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        Claim first = a.claim("k").orElseThrow();
        a.release(first);
        await().atMost(10, TimeUnit.SECONDS).until(() -> !a.contains("k"));
        assertEquals(0, a.registrySize());

        Claim second = a.claim("k").orElseThrow();
        assertNotEquals(first.claimId(), second.claimId());
        a.confirm(second);
        assertEquals(List.of("add", "remove", "add"), values());
        KAFKA.readAll(topic).forEach(r -> assertTrue(r.headers().lastHeader(IdempotentTopicContract.CLAIM_ID_HEADER) != null));
    }

    // (d)
    @Test
    void clearWrittenByCamelEmptiesTheRepository(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        a.confirm(a.claim("k1").orElseThrow());
        a.confirm(a.claim("k2").orElseThrow());
        assertEquals(2, a.size());
        try (KafkaProducer<String, String> camel = KAFKA.rawProducer()) {
            camel.send(new ProducerRecord<>(topic, null, "clear")).get();
        }
        await().atMost(10, TimeUnit.SECONDS).until(() -> a.size() == 0);
        a.confirm(a.claim("k1").orElseThrow());
    }

    // (e)
    @Test
    void keysSpreadOverPartitionsAndReplayCompletely(TestInfo info) {
        topic = "idempotent-" + info.getTestMethod().orElseThrow().getName();
        KAFKA.createTopic(topic, 3);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        for (int i = 0; i < 60; i++) {
            a.confirm(a.claim("key-" + i).orElseThrow());
        }
        Set<Integer> partitions = KAFKA.readAll(topic).stream().map(ConsumerRecord::partition).collect(Collectors.toSet());
        assertTrue(partitions.size() >= 2, "keys hash to several partitions: " + partitions);

        KafkaClaimRepository fresh = repository("b", TIMEOUTS);
        assertEquals(60, fresh.size());
        assertTrue(fresh.claim("key-59").isEmpty());
    }

    // (f) A claim whose outcome arrives after the caller gave up is released, so the key does not stay dead.
    @Test
    void abandonedClaimThatWonIsReleased(TestInfo info) {
        topic(info);
        KafkaClaimRepository a = repository("a", SHORT_CLAIM);
        a.poller().pause();
        assertThrows(ClaimTimeoutException.class, () -> a.claim("k"));
        a.poller().resume();
        await().atMost(10, TimeUnit.SECONDS).until(() -> values().equals(List.of("add", "remove")));
        await().atMost(10, TimeUnit.SECONDS).until(() -> !a.contains("k") && a.registrySize() == 0);

        KafkaClaimRepository b = repository("b", TIMEOUTS);
        b.confirm(b.claim("k").orElseThrow());
    }

    // (f2) A retry on the same pod re-attaches to the claim in flight instead of writing a second one.
    @Test
    void retryOnTheSamePodReattachesToTheClaimInFlight(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", SHORT_CLAIM);
        a.poller().pause();
        assertThrows(ClaimTimeoutException.class, () -> a.claim("k"));
        CompletableFuture<Optional<Claim>> retry = CompletableFuture.supplyAsync(() -> a.claim("k"));
        a.poller().resume();
        Claim claim = retry.get(10, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Optional.of(claim.claimId()), a.owner("k"));
        assertEquals(List.of("add"), values(), "one add, no remove");
        a.confirm(claim);
        assertEquals(0, a.registrySize());
    }

    // (g)
    @Test
    void missingTopicFailsFastAndEmptyTopicStartsEmpty(TestInfo info) {
        topic = "idempotent-does-not-exist";
        Map<String, Object> props = KAFKA.clientProperties();
        KafkaClaimRepository missing = new KafkaClaimRepository(topic, props, props, "a",
                new KafkaClaimRepository.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofMillis(100)));
        long startedAt = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class, missing::start);
        assertTrue(e.getMessage().contains(topic), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(20)) < 0);
        assertFalse(missing.isRunning());

        topic(info);
        KafkaClaimRepository empty = repository("b", TIMEOUTS);
        assertEquals(0, empty.size());
        assertTrue(empty.isAlive());
    }

    // (h) Publish fails and the release fails too (broker outage); the retry on the same pod publishes exactly once.
    // Mutation check: without the local registry the retry would see its own dead claim and skip the event.
    @Test
    void releaseThatFailsIsRetriedAndTheNextAttemptPublishesOnce(TestInfo info) throws Exception {
        topic(info);
        FailableProducer failable = new FailableProducer();
        KafkaClaimRepository a = repository("a", TIMEOUTS, config -> failable.wrap(config, TIMEOUTS));
        Claim first = a.claim("k").orElseThrow();

        failable.failSends(true);
        a.release(first);                       // publish failed, and now the release fails as well
        await().atMost(10, TimeUnit.SECONDS).until(() -> failable.failedSends() >= 2);
        assertEquals(List.of("add"), values());

        // the next attempt runs while the release is still being retried, then the broker comes back
        CompletableFuture<Optional<Claim>> attempt = CompletableFuture.supplyAsync(() -> a.claim("k"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> failable.failedSends() >= 4);
        assertFalse(attempt.isDone(), "the attempt waits for the release to land");
        failable.failSends(false);

        Claim second = attempt.get(10, TimeUnit.SECONDS).orElseThrow();
        assertNotEquals(first.claimId(), second.claimId());
        a.confirm(second);
        assertEquals(List.of("add", "remove", "add"), values());
        await().atMost(10, TimeUnit.SECONDS).until(() -> a.owner("k").equals(Optional.of(second.claimId())));
        assertEquals(0, a.registrySize());

        KafkaClaimRepository b = repository("b", TIMEOUTS);
        assertTrue(b.claim("k").isEmpty(), "the event was published exactly once");
    }

    // (h2) Same, but the broker is back before the next attempt.
    @Test
    void releaseRetryLandsBeforeTheNextAttempt(TestInfo info) throws Exception {
        topic(info);
        FailableProducer failable = new FailableProducer();
        KafkaClaimRepository a = repository("a", TIMEOUTS, config -> failable.wrap(config, TIMEOUTS));
        Claim first = a.claim("k").orElseThrow();
        failable.failSends(true);
        a.release(first);
        await().atMost(10, TimeUnit.SECONDS).until(() -> failable.failedSends() >= 2);
        failable.failSends(false);
        await().atMost(10, TimeUnit.SECONDS).until(() -> !a.contains("k") && a.registrySize() == 0);

        Claim second = a.claim("k").orElseThrow();
        a.confirm(second);
        assertEquals(List.of("add", "remove", "add"), values());
        KafkaClaimRepository b = repository("b", TIMEOUTS);
        assertTrue(b.claim("k").isEmpty());
    }

    // Publish failed, release landed: Camel's removeOnFailure parity on the same pod.
    @Test
    void publishFailureOnTheSamePodIsRetriedAfterTheReleaseLanded(TestInfo info) {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        Claim first = a.claim("k").orElseThrow();
        a.release(first);
        Claim second = a.claim("k").orElseThrow();      // waits for the remove if it has not landed yet
        assertNotEquals(first.claimId(), second.claimId());
        a.confirm(second);
        await().atMost(10, TimeUnit.SECONDS).until(() -> a.owner("k").equals(Optional.of(second.claimId())));
        assertEquals(List.of("add", "remove", "add"), values());
    }

    @Test
    void releaseIsRefusedForAClaimThatIsNotTheOwner(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        try (KafkaProducer<String, String> camel = KAFKA.rawProducer()) {
            camel.send(new ProducerRecord<>(topic, "k", "add")).get();
        }
        await().atMost(10, TimeUnit.SECONDS).until(() -> a.contains("k"));
        a.release(new Claim("k", "a:not-mine"));
        a.release(new Claim("other", "a:unknown"));
        assertEquals(List.of("add"), values(), "nothing was written");
        assertTrue(a.contains("k"));
    }

    // A send that the producer gives up on fails the claim at once. The claim is kept until the poller has read
    // past where the add could have landed, then forgotten; the next claim is fresh.
    @Test
    void failedAddFailsTheClaimAndIsForgottenOnceThePollerHasReadPastIt(TestInfo info) {
        topic(info);
        FailableProducer failable = new FailableProducer();
        KafkaClaimRepository a = repository("a", SHORT_SEND, config -> failable.wrap(config, SHORT_SEND));
        failable.failSends(true);
        assertThrows(ClaimException.class, () -> a.claim("k"));
        assertEquals(1, a.registrySize(), "the claim is kept while the add may still show up");
        assertEquals(1, a.pendingFutures());
        assertEquals(List.of(), values());
        await().atMost(10, TimeUnit.SECONDS).until(() -> a.registrySize() == 0 && a.pendingFutures() == 0);

        failable.failSends(false);
        a.confirm(a.claim("k").orElseThrow());
        assertEquals(List.of("add"), values());
    }

    // A retry on the same pod during that window re-attaches to the claim and fails once it is known never to
    // have been written; the retry after that claims fresh.
    @Test
    void retryAfterAFailedAddWaitsUntilTheAddIsKnownNeverWritten(TestInfo info) throws Exception {
        topic(info);
        FailableProducer failable = new FailableProducer();
        KafkaClaimRepository a = repository("a", SHORT_SEND, config -> failable.wrap(config, SHORT_SEND));
        failable.failSends(true);
        assertThrows(ClaimException.class, () -> a.claim("k"));
        failable.failSends(false);

        CompletableFuture<Optional<Claim>> retry = CompletableFuture.supplyAsync(() -> a.claim("k"));
        assertFalse(retry.isDone());
        ClaimException e = assertThrows(ClaimException.class, () -> {
            try {
                retry.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ex) {
                throw ex.getCause();
            }
        });
        assertTrue(e.getMessage().contains("never written"), e.getMessage());
        assertEquals(List.of(), values(), "the retry wrote no second add while the first was undecided");

        a.confirm(a.claim("k").orElseThrow());
        assertEquals(List.of("add"), values());
    }

    // The producer gave up on an add that did land (acknowledgement lost): the claim won after its caller
    // gave up, so it is released, and the key does not stay owned by a claim nobody will ever release.
    @Test
    void addWhoseAcknowledgementWasLostIsReleased(TestInfo info) {
        topic(info);
        FailableProducer failable = new FailableProducer();
        KafkaClaimRepository a = repository("a", SHORT_SEND, config -> failable.wrap(config, SHORT_SEND));
        failable.loseAcknowledgements(true);
        assertThrows(ClaimException.class, () -> a.claim("k"));
        failable.loseAcknowledgements(false);
        await().atMost(10, TimeUnit.SECONDS).until(() -> values().equals(List.of("add", "remove")));
        await().atMost(10, TimeUnit.SECONDS).until(() -> !a.contains("k") && a.registrySize() == 0);

        KafkaClaimRepository b = repository("b", TIMEOUTS);
        b.confirm(b.claim("k").orElseThrow());
    }

    // Same, but a retry on the same pod re-attaches before the add is read back: it re-owns the claim and
    // publishes, with no second add and no remove.
    @Test
    void retryReownsTheAddWhoseAcknowledgementWasLost(TestInfo info) throws Exception {
        topic(info);
        FailableProducer failable = new FailableProducer();
        KafkaClaimRepository a = repository("a", SHORT_SEND, config -> failable.wrap(config, SHORT_SEND));
        a.poller().pause();
        failable.loseAcknowledgements(true);
        assertThrows(ClaimException.class, () -> a.claim("k"));
        failable.loseAcknowledgements(false);

        CompletableFuture<Optional<Claim>> retry = new CompletableFuture<>();
        Thread retrying = new Thread(() -> retry.complete(a.claim("k")));
        retrying.start();
        await().atMost(10, TimeUnit.SECONDS).until(() -> retrying.getState() == Thread.State.TIMED_WAITING);
        a.poller().resume();

        Claim claim = retry.get(10, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Optional.of(claim.claimId()), a.owner("k"));
        a.confirm(claim);
        assertEquals(List.of("add"), values(), "one add, no remove");
        assertEquals(0, a.registrySize());
    }

    // A claim still held when the repository stops (the drain gave up on its notification) is released, so
    // that the redelivery can publish the event.
    @Test
    void unconfirmedClaimIsReleasedAtStop(TestInfo info) {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        Claim held = a.claim("k").orElseThrow();
        a.stop();
        assertEquals(List.of("add", "remove"), values());

        KafkaClaimRepository b = repository("b", TIMEOUTS);
        assertFalse(b.contains("k"));
        Claim fresh = b.claim("k").orElseThrow();
        assertNotEquals(held.claimId(), fresh.claimId());
        b.confirm(fresh);
    }

    // A release that arrives while the repository is stopping is sent through the producer before it closes.
    @Test
    void releaseDuringStopIsSent(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        Claim held = a.claim("k").orElseThrow();
        Thread releasing = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            a.release(held);
        });
        releasing.start();
        a.stop();
        releasing.join(5000);
        List<String> written = values();
        assertEquals("add", written.getFirst());
        assertTrue(written.size() >= 2 && written.subList(1, written.size()).stream().allMatch("remove"::equals), written.toString());

        KafkaClaimRepository b = repository("b", TIMEOUTS);
        assertFalse(b.contains("k"));
    }

    // One holder per key on one pod: a second caller waits for the first to confirm or release.
    @Test
    void secondCallerForTheSameKeyWaitsForTheHolder(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        Claim first = a.claim("k").orElseThrow();
        CompletableFuture<Optional<Claim>> second = CompletableFuture.supplyAsync(() -> a.claim("k"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> !second.isDone() && a.registrySize() == 1);
        a.confirm(first);
        assertTrue(second.get(10, TimeUnit.SECONDS).isEmpty(), "the holder published, the second caller skips");

        KafkaClaimRepository b = repository("b", TIMEOUTS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> b.contains("k"));
        // the same with a release: the second caller gets a fresh claim and publishes instead
        Claim third = b.claim("k2").orElseThrow();
        CompletableFuture<Optional<Claim>> fourth = CompletableFuture.supplyAsync(() -> b.claim("k2"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> !fourth.isDone() && b.registrySize() == 1);
        b.release(third);
        Claim fresh = fourth.get(10, TimeUnit.SECONDS).orElseThrow();
        assertNotEquals(third.claimId(), fresh.claimId());
        b.confirm(fresh);
    }

    // (i)
    @Test
    void stopJoinsThePollerAndFailsPendingClaims(TestInfo info) throws Exception {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        a.poller().pause();
        CompletableFuture<Optional<Claim>> pending = CompletableFuture.supplyAsync(() -> a.claim("k"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> KAFKA.recordCount(topic) == 1);
        a.stop();
        assertThrows(Exception.class, () -> pending.get(10, TimeUnit.SECONDS));
        assertFalse(a.isAlive());
        assertThrows(ClaimException.class, () -> a.claim("k2"), "a stopped repository refuses claims");
        assertFalse(a.poller().isAlive());
        assertEquals(0, a.registrySize());
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("idempotent-repository-poller") && t.isAlive()));

        MBeanServer mbeans = ManagementFactory.getPlatformMBeanServer();
        Set<ObjectName> left = mbeans.queryNames(new ObjectName("kafka.*:*"), null).stream()
                .filter(n -> "nisaba-idempotent-repo-a".equals(n.getKeyProperty("client-id")))
                .collect(Collectors.toSet());
        assertTrue(left.isEmpty(), "client MBeans left after stop: " + left);
    }

    // Spike criteria: replay speed and claim latency, logged.
    @Test
    void replayOfTwentyThousandRecordsIsFast(TestInfo info) throws Exception {
        topic = "idempotent-" + info.getTestMethod().orElseThrow().getName();
        KAFKA.createTopic(topic, 3);
        try (KafkaProducer<String, String> camel = KAFKA.rawProducer()) {
            List<java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata>> sent = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) {
                sent.add(camel.send(new ProducerRecord<>(topic, "key-" + i, "add")));
            }
            for (var f : sent) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        long startedAt = System.nanoTime();
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
        LOGGER.info("Replay of 20000 records over 3 partitions took {} ms", took.toMillis());
        assertEquals(20_000, a.size());
        assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, "replay took " + took);
    }

    @Test
    void claimLatency(TestInfo info) {
        topic(info);
        KafkaClaimRepository a = repository("a", TIMEOUTS);
        long[] nanos = new long[200];
        for (int i = 0; i < nanos.length; i++) {
            long startedAt = System.nanoTime();
            a.confirm(a.claim("key-" + i).orElseThrow());
            nanos[i] = System.nanoTime() - startedAt;
        }
        Arrays.sort(nanos);
        LOGGER.info("Claim latency over {} claims: p50 {} ms, p99 {} ms, max {} ms", nanos.length,
                nanos[nanos.length / 2] / 1_000_000, nanos[nanos.length * 99 / 100] / 1_000_000, nanos[nanos.length - 1] / 1_000_000);
    }

}
