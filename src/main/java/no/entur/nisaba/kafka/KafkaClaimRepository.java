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

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static no.entur.nisaba.kafka.IdempotentTopicContract.ACTION_ADD;
import static no.entur.nisaba.kafka.IdempotentTopicContract.ACTION_REMOVE;
import static no.entur.nisaba.kafka.IdempotentTopicContract.CLAIM_ID_HEADER;
import static no.entur.nisaba.kafka.IdempotentTopicContract.CLIENT_ID_REPOSITORY;

/**
 * Idempotent repository backed by the Kafka topic Camel's {@code KafkaIdempotentRepository} used, with a claim
 * that is atomic across pods.
 * <p>
 * A claim writes {@code (key, "add")} with a claim id header and waits until its own record has been read back:
 * the key is new exactly when that record is the first {@code add} since the last {@code remove}, which Kafka's
 * per-key ordering decides identically on every pod. Nothing else is coordinated, so no leader election is
 * needed. The record format is unchanged, so the Camel version reads the records written here, and vice versa.
 * <p>
 * A local registry of the claims this JVM issued closes the gaps a log alone would leave open: a retry on the
 * same pod re-attaches to a claim whose outcome is still unknown, or re-owns one whose publish failed and whose
 * release did not land, instead of producing a second claim and then skipping its own key; a claim whose
 * outcome arrived after the caller gave up is released, so a redelivery can publish the event; and a key is
 * held by one caller at a time, so two notifications for one key on one pod cannot both publish.
 */
public class KafkaClaimRepository implements ImportEventClaimRepository, SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaClaimRepository.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_BLOCK = Duration.ofSeconds(10);

    public record Timeouts(Duration sendTimeout, Duration claimTimeout, Duration replayTimeout, Duration pollTimeout) {
    }

    private enum LocalState {
        /** The add has been handed to the producer and a caller is waiting for its outcome. */
        PENDING,
        /** The add has been handed to the producer but no caller is waiting any more. */
        ABANDONED,
        /** The claim won and its event has not been confirmed published. */
        OWNED,
        /** A remove has been started for the claim. */
        RELEASING
    }

    private enum Step {
        PRODUCE,
        AWAIT,
        AWAIT_REMOVE,
        AWAIT_HOLDER
    }

    private final class LocalClaim {
        private final String key;
        private final String claimId;
        private final CompletableFuture<Boolean> outcome;
        private final CompletableFuture<Void> removeAcked = new CompletableFuture<>();
        private LocalState localState = LocalState.PENDING;
        /** True while a caller may still act on the claim; one caller at a time. */
        private boolean held = true;
        private CompletableFuture<Void> settled = new CompletableFuture<>();

        private LocalClaim(String key) {
            this.key = key;
            this.claimId = instanceId + ':' + UUID.randomUUID();
            this.outcome = topicState.expectAdd(claimId);
        }

        private Claim claim() {
            return new Claim(key, claimId);
        }

        private void hold() {
            if (!held) {
                held = true;
                settled = new CompletableFuture<>();
            }
        }

        private void settle() {
            if (held) {
                held = false;
                settled.complete(null);
            }
        }
    }

    private final String topic;
    private final Map<String, Object> producerConfig;
    private final Map<String, Object> consumerConfig;
    private final String instanceId;
    private final Timeouts timeouts;
    private final Function<Map<String, Object>, Producer<String, String>> producerFactory;

    private final IdempotentTopicState topicState = new IdempotentTopicState();
    private final Object lock = new Object();
    private final Map<String, LocalClaim> registry = new HashMap<>();

    private volatile boolean running;
    private Producer<String, String> producer;
    private AssignedTopicPoller<String, String> poller;
    private ScheduledExecutorService releaseExecutor;

    public KafkaClaimRepository(String topic, Map<String, Object> producerConfig, Map<String, Object> consumerConfig,
                                String instanceId, Timeouts timeouts) {
        this(topic, producerConfig, consumerConfig, instanceId, timeouts, config -> createProducer(config, timeouts));
    }

    /**
     * Test hook: lets a test wrap the producer to simulate a broker outage.
     */
    KafkaClaimRepository(String topic, Map<String, Object> producerConfig, Map<String, Object> consumerConfig,
                         String instanceId, Timeouts timeouts, Function<Map<String, Object>, Producer<String, String>> producerFactory) {
        this.topic = topic;
        this.producerConfig = producerConfig;
        this.consumerConfig = consumerConfig;
        this.instanceId = instanceId;
        this.timeouts = timeouts;
        this.producerFactory = producerFactory;
    }

    // --- lifecycle ---

    @Override
    public void start() {
        if (running) {
            return;
        }
        long startedAt = System.nanoTime();
        producer = producerFactory.apply(producerConfig);
        releaseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotent-repository-release");
            t.setDaemon(true);
            return t;
        });
        poller = new AssignedTopicPoller<>("idempotent-repository", topic,
                () -> new KafkaConsumer<>(consumerProperties(consumerConfig)),
                this::applyRecord, timeouts.pollTimeout(), timeouts.replayTimeout());
        running = true;
        LOGGER.info("Syncing idempotent repository from topic {} starting", topic);
        try {
            poller.start();
        } catch (RuntimeException e) {
            running = false;
            releaseExecutor.shutdownNow();
            producer.close(CLOSE_TIMEOUT);
            throw e;
        }
        LOGGER.info("Syncing idempotent repository from topic {} complete: {} keys in {} ms", topic, topicState.size(),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        releaseExecutor.shutdownNow();
        flushPendingReleases();
        poller.stop(CLOSE_TIMEOUT);
        producer.close(CLOSE_TIMEOUT);
        topicState.failPending(new ClaimException("Idempotent repository stopped"));
        synchronized (lock) {
            registry.values().forEach(LocalClaim::settle);
            registry.clear();
        }
        LOGGER.info("Stopped idempotent repository on topic {}", topic);
    }

    /**
     * A release whose retries are cut short by the shutdown would leave the key claimed by a dead claim: send
     * every pending remove once more; {@code producer.close} flushes them.
     */
    private void flushPendingReleases() {
        List<LocalClaim> releasing = new ArrayList<>();
        synchronized (lock) {
            registry.values().stream().filter(c -> c.localState == LocalState.RELEASING && !c.removeAcked.isDone()).forEach(releasing::add);
        }
        for (LocalClaim local : releasing) {
            LOGGER.warn("Sending the pending release of key {} (claim {}) before stopping", local.key, local.claimId);
            try {
                producer.send(record(local.key, ACTION_REMOVE, local.claimId));
            } catch (RuntimeException e) {
                LOGGER.error("Could not send the pending release of key {} (claim {}); the key stays claimed", local.key, local.claimId, e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    /**
     * @return whether the poller thread is alive; when it is not, no claim can be decided.
     */
    public boolean isAlive() {
        AssignedTopicPoller<String, String> p = poller;
        return running && p != null && p.isAlive();
    }

    public int size() {
        return topicState.size();
    }

    public boolean contains(String key) {
        return topicState.contains(key);
    }

    public Optional<String> owner(String key) {
        return topicState.owner(key);
    }

    AssignedTopicPoller<String, String> poller() {
        return poller;
    }

    int registrySize() {
        synchronized (lock) {
            return registry.size();
        }
    }

    int pendingFutures() {
        return topicState.pendingCount();
    }

    private void applyRecord(ConsumerRecord<String, String> record) {
        topicState.apply(record.key(), record.value(), IdempotentTopicContract.claimId(record.headers()),
                record.topic(), record.partition(), record.offset());
    }

    // --- claim / confirm / release ---

    @Override
    public Optional<Claim> claim(String key) {
        boolean skipFastPath = false;
        while (true) {
            LocalClaim local;
            Step step;
            synchronized (lock) {
                ensureRunning();
                local = registry.get(key);
                if (local == null) {
                    if (!skipFastPath && topicState.contains(key)) {
                        LOGGER.debug("Key {} is already present, no claim written", key);
                        return Optional.empty();
                    }
                    local = register(key);
                    step = Step.PRODUCE;
                } else if (local.held) {
                    step = Step.AWAIT_HOLDER;
                } else {
                    switch (local.localState) {
                        case PENDING, ABANDONED -> {
                            LOGGER.info("Re-attaching to claim {} for key {} whose outcome is still unknown", local.claimId, key);
                            local.localState = LocalState.PENDING;
                            local.hold();
                            step = Step.AWAIT;
                        }
                        case OWNED -> {
                            LOGGER.info("Re-owning claim {} for key {}", local.claimId, key);
                            local.hold();
                            return Optional.of(local.claim());
                        }
                        case RELEASING -> step = Step.AWAIT_REMOVE;
                        default -> throw new IllegalStateException("Unexpected state " + local.localState);
                    }
                }
            }
            switch (step) {
                case PRODUCE -> {
                    produceAdd(local);
                    return await(local);
                }
                case AWAIT -> {
                    return await(local);
                }
                case AWAIT_REMOVE -> {
                    awaitRemove(local);
                    synchronized (lock) {
                        if (registry.get(key) == local) {
                            registry.remove(key);
                        }
                    }
                    // the remove is durable: a new add lands after it, whatever the local view says meanwhile
                    skipFastPath = true;
                }
                case AWAIT_HOLDER -> awaitHolder(local);
            }
        }
    }

    @Override
    public void confirm(Claim claim) {
        synchronized (lock) {
            LocalClaim local = registry.get(claim.importKey());
            if (local == null || !local.claimId.equals(claim.claimId())) {
                return;
            }
            if (local.localState != LocalState.OWNED) {
                LOGGER.warn("Not confirming key {}: claim {} is in state {}", claim.importKey(), claim.claimId(), local.localState);
                return;
            }
            evict(local);
        }
    }

    @Override
    public void release(Claim claim) {
        LocalClaim local;
        synchronized (lock) {
            local = registry.get(claim.importKey());
            if (local == null || !local.claimId.equals(claim.claimId())) {
                LOGGER.warn("Not releasing key {}: claim {} is not registered", claim.importKey(), claim.claimId());
                return;
            }
            if (local.localState != LocalState.OWNED) {
                LOGGER.warn("Not releasing key {}: claim {} is in state {}", claim.importKey(), claim.claimId(), local.localState);
                return;
            }
            Optional<String> owner = topicState.owner(claim.importKey());
            if (!owner.equals(Optional.of(claim.claimId()))) {
                LOGGER.warn("Not releasing key {}: owned by {} not {}", claim.importKey(), owner.orElse("nobody"), claim.claimId());
                evict(local);
                return;
            }
            transitionToReleasing(local);
        }
        scheduleRemove(local, Duration.ZERO);
    }

    // --- internals (registry access under lock) ---

    private void ensureRunning() {
        if (!running) {
            throw new ClaimException("Idempotent repository is not running");
        }
        if (!poller.isAlive()) {
            throw new ClaimException("The poller of the idempotent topic " + topic + " is not alive");
        }
    }

    private LocalClaim register(String key) {
        LocalClaim local = new LocalClaim(key);
        registry.put(key, local);
        local.outcome.whenComplete((won, ex) -> onOutcome(local, won, ex));
        return local;
    }

    private void evict(LocalClaim local) {
        if (registry.get(local.key) == local) {
            registry.remove(local.key);
        }
        topicState.forget(local.claimId);
        local.settle();
    }

    private void transitionToReleasing(LocalClaim local) {
        local.localState = LocalState.RELEASING;
        local.settle();
        topicState.expectRemove(local.claimId).whenComplete((v, ex) -> {
            synchronized (lock) {
                evict(local);
            }
        });
    }

    /**
     * Runs when the outcome of a claim becomes known. Only acts on claims nobody is waiting for any more.
     */
    private void onOutcome(LocalClaim local, Boolean won, Throwable ex) {
        synchronized (lock) {
            if (local.localState != LocalState.ABANDONED) {
                return;
            }
            if (ex != null || !Boolean.TRUE.equals(won)) {
                evict(local);
                return;
            }
            LOGGER.warn("Abandoned claim {} for key {} won after its caller gave up; releasing it so that a redelivery can publish the event",
                    local.claimId, local.key);
            local.localState = LocalState.OWNED;
            transitionToReleasing(local);
        }
        scheduleRemove(local, Duration.ZERO);
    }

    /**
     * Hands the add to the producer. The outcome is decided by the poller when the record is read back, or
     * failed by the producer when it gives up on the record (bounded by {@code delivery.timeout.ms}).
     */
    private void produceAdd(LocalClaim local) {
        try {
            producer.send(record(local.key, ACTION_ADD, local.claimId), (metadata, exception) -> {
                if (exception == null) {
                    LOGGER.debug("Claim {} for key {} written at partition {} offset {}", local.claimId, local.key,
                            metadata.partition(), metadata.offset());
                } else {
                    onAddFailed(local, exception);
                }
            });
        } catch (RuntimeException e) {
            onAddFailed(local, e);
            throw new ClaimException("Could not write the claim for key " + local.key + " to topic " + topic, e);
        }
    }

    private void onAddFailed(LocalClaim local, Exception exception) {
        if (!(exception instanceof RetriableException)) {
            LOGGER.error("The producer of the idempotent topic {} failed: {}", topic, exception.toString());
        }
        synchronized (lock) {
            if (local.localState == LocalState.PENDING || local.localState == LocalState.ABANDONED) {
                evict(local);
            }
        }
        local.outcome.completeExceptionally(new ClaimException("Could not write the claim for key " + local.key
                + " to topic " + topic, exception));
    }

    private Optional<Claim> await(LocalClaim local) {
        Boolean won;
        Duration wait = timeouts.sendTimeout().plus(timeouts.claimTimeout());
        try {
            won = local.outcome.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (lock) {
                if (!local.outcome.isDone()) {
                    local.localState = LocalState.ABANDONED;
                    local.settle();
                    throw new ClaimTimeoutException("Claim " + local.claimId + " for key " + local.key
                            + " is not visible after " + wait);
                }
            }
            return await(local);
        } catch (ExecutionException e) {
            synchronized (lock) {
                evict(local);
            }
            if (e.getCause() instanceof ClaimException claimException) {
                throw claimException;
            }
            throw new ClaimException("Claim " + local.claimId + " for key " + local.key + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                local.localState = LocalState.ABANDONED;
                local.settle();
            }
            throw new ClaimException("Interrupted while waiting for claim " + local.claimId + " for key " + local.key, e);
        }
        synchronized (lock) {
            if (Boolean.TRUE.equals(won)) {
                local.localState = LocalState.OWNED;
                LOGGER.info("Claimed key {} with claim {}", local.key, local.claimId);
                return Optional.of(local.claim());
            }
            evict(local);
            LOGGER.info("Lost claim {} for key {}: owner is {}", local.claimId, local.key,
                    topicState.owner(local.key).orElse("nobody"));
            return Optional.empty();
        }
    }

    private void awaitRemove(LocalClaim local) {
        try {
            local.removeAcked.get(timeouts.claimTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ClaimException("The previous claim " + local.claimId + " for key " + local.key
                    + " is still being released after " + timeouts.claimTimeout());
        } catch (ExecutionException e) {
            throw new ClaimException("The release of the previous claim " + local.claimId + " for key " + local.key
                    + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaimException("Interrupted while waiting for the release of claim " + local.claimId, e);
        }
    }

    private void awaitHolder(LocalClaim local) {
        try {
            local.settled.get(timeouts.claimTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ClaimException("Key " + local.key + " is still held by claim " + local.claimId
                    + " after " + timeouts.claimTimeout());
        } catch (ExecutionException e) {
            throw new ClaimException("Waiting for the holder of key " + local.key + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaimException("Interrupted while waiting for the holder of key " + local.key, e);
        }
    }

    private void scheduleRemove(LocalClaim local, Duration delay) {
        try {
            releaseExecutor.schedule(() -> sendRemove(local), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.warn("Not scheduling the release of key {} (claim {}): the repository is stopping", local.key, local.claimId);
        }
    }

    private void sendRemove(LocalClaim local) {
        if (!running) {
            return;
        }
        try {
            producer.send(record(local.key, ACTION_REMOVE, local.claimId), (metadata, exception) -> {
                if (exception == null) {
                    local.removeAcked.complete(null);
                    LOGGER.warn("Released key {} (claim {}) so that it can be claimed again", local.key, local.claimId);
                } else {
                    retryRemove(local, exception);
                }
            });
        } catch (RuntimeException e) {
            retryRemove(local, e);
        }
    }

    private void retryRemove(LocalClaim local, Throwable cause) {
        if (!running) {
            return;
        }
        if (!(cause instanceof RetriableException)) {
            LOGGER.error("The producer of the idempotent topic {} failed: {}", topic, cause.toString());
        }
        LOGGER.warn("Release of key {} (claim {}) failed: {}. Retrying in {}", local.key, local.claimId,
                cause.toString(), timeouts.pollTimeout());
        scheduleRemove(local, timeouts.pollTimeout());
    }

    private ProducerRecord<String, String> record(String key, String action, String claimId) {
        return new ProducerRecord<>(topic, null, key, action,
                List.of(new RecordHeader(CLAIM_ID_HEADER, IdempotentTopicContract.claimIdHeaderValue(claimId))));
    }

    // --- client configuration ---

    /**
     * The producer gives up on a record within the send timeout, so that a claim whose add failed is known to
     * have failed, and never blocks a caller for longer than {@link #MAX_BLOCK}.
     */
    static Producer<String, String> createProducer(Map<String, Object> config, Timeouts timeouts) {
        Map<String, Object> props = new HashMap<>(config);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, CLIENT_ID_REPOSITORY);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        forceDurable(props);
        long sendMillis = timeouts.sendTimeout().toMillis();
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) sendMillis);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.max(1000, sendMillis / 2));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) Math.min(MAX_BLOCK.toMillis(), sendMillis));
        return new KafkaProducer<>(props);
    }

    /**
     * The claim protocol reads its own record back: an acknowledged record must be durable, whatever
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

    static Map<String, Object> consumerProperties(Map<String, Object> config) {
        Map<String, Object> props = new HashMap<>(config);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, CLIENT_ID_REPOSITORY);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        props.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        return props;
    }
}
