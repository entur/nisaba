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

import no.entur.nisaba.idempotent.MarkerStore;
import no.entur.nisaba.kafka.AssignedTopicPoller;
import no.entur.nisaba.kafka.IdempotentTopicContract;
import no.entur.nisaba.kafka.IdempotentTopicState;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Copies the history of the Kafka idempotent topic into the marker store of the GCS idempotent repository:
 * the topic is replayed from the beginning, and every key present at the end (the first {@code add} after
 * the last {@code remove}) gets a confirmed marker, unless it has one already.
 * <p>
 * Running it again only creates what is missing, so it can be run before the deployment and once more after
 * it, to pick up what the previous version wrote during the rolling update. Nothing is ever overwritten or
 * deleted, and the topic is only read.
 */
public final class IdempotentRepositoryMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentRepositoryMigration.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Prefix of the claim id of a migrated marker, followed by the claim id found on the topic. */
    public static final String MIGRATED_PREFIX = "migrated:";
    /** What follows the prefix for a record written by the Camel version, which has no claim id. */
    public static final String CAMEL_OWNER = "camel";

    /**
     * @param keysOnTopic    keys present on the topic
     * @param created        markers created (in a dry run: markers that would be created)
     * @param alreadyPresent keys that had a marker already
     * @param failed         keys whose marker could not be written or read
     */
    public record Result(int keysOnTopic, int created, int alreadyPresent, int failed) {
        public boolean successful() {
            return failed == 0;
        }
    }

    private final String topic;
    private final Map<String, Object> consumerConfig;
    private final MarkerStore store;
    private final boolean dryRun;
    private final int threads;
    private final Duration pollTimeout;
    private final Duration replayTimeout;

    public IdempotentRepositoryMigration(String topic, Map<String, Object> consumerConfig, MarkerStore store,
                                         boolean dryRun, int threads, Duration pollTimeout, Duration replayTimeout) {
        this.topic = topic;
        this.consumerConfig = consumerConfig;
        this.store = store;
        this.dryRun = dryRun;
        this.threads = Math.max(1, threads);
        this.pollTimeout = pollTimeout;
        this.replayTimeout = replayTimeout;
    }

    public Result run() {
        Map<String, String> owners = new TreeMap<>(replay());
        LOGGER.info("{} keys are present on topic {}", owners.size(), topic);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger alreadyPresent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, String> entry : owners.entrySet()) {
                futures.add(executor.submit(() -> migrate(entry.getKey(), entry.getValue(), created, alreadyPresent, failed)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    failed.incrementAndGet();
                    LOGGER.error("Migration task failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while migrating the idempotent repository", e);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        Result result = new Result(owners.size(), created.get(), alreadyPresent.get(), failed.get());
        LOGGER.info("Migration of topic {} {}: {} keys on the topic, {} {}, {} already present, {} failed",
                topic, dryRun ? "dry run" : "done", result.keysOnTopic(), result.created(),
                dryRun ? "would be created" : "created", result.alreadyPresent(), result.failed());
        return result;
    }

    private void migrate(String key, String owner, AtomicInteger created, AtomicInteger alreadyPresent, AtomicInteger failed) {
        String claimId = MIGRATED_PREFIX + (owner.isEmpty() ? CAMEL_OWNER : owner);
        try {
            if (dryRun) {
                if (store.read(key).isPresent()) {
                    alreadyPresent.incrementAndGet();
                } else {
                    created.incrementAndGet();
                    LOGGER.info("Would create {} ({})", store.location(key), claimId);
                }
            } else if (store.createIfAbsent(key, claimId, true).isPresent()) {
                created.incrementAndGet();
                LOGGER.info("Created {} ({})", store.location(key), claimId);
            } else {
                alreadyPresent.incrementAndGet();
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            LOGGER.error("Could not migrate key {} to {}: {}", key, store.location(key), e.toString());
        }
    }

    /**
     * @return the keys present on the topic, each with the claim id that owns it.
     */
    private Map<String, String> replay() {
        IdempotentTopicState state = new IdempotentTopicState();
        AssignedTopicPoller<String, String> poller = new AssignedTopicPoller<>("idempotent-repository-migration", topic,
                () -> new KafkaConsumer<>(consumerProperties(consumerConfig)),
                record -> state.apply(record.key(), record.value(), IdempotentTopicContract.claimId(record.headers()),
                        record.topic(), record.partition(), record.offset()),
                pollTimeout, replayTimeout);
        // returns once everything the topic held at the start has been applied
        poller.start();
        poller.stop(CLOSE_TIMEOUT);
        return state.snapshot();
    }

    static Map<String, Object> consumerProperties(Map<String, Object> config) {
        return AssignedTopicPoller.consumerProperties(config, IdempotentTopicContract.CLIENT_ID_REPOSITORY + "-migration",
                StringDeserializer.class, StringDeserializer.class);
    }
}
