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

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Reads a whole topic from the beginning on its own thread and keeps following it.
 * <p>
 * Replaces both Camel pollers nisaba had: the one inside {@code KafkaIdempotentRepository} and the
 * {@code from("kafka:...?seekTo=beginning")} route. Like them it assigns every partition itself and uses no
 * consumer group, so each pod has its own full copy and no group ACL is needed. {@link #start()} blocks until the
 * topic has been replayed up to the end offsets observed at start, and fails fast when the topic does not exist.
 * <p>
 * The {@link Consumer} is created, used and closed on the poller thread only, as the Kafka consumer is not
 * thread-safe; {@link Consumer#wakeup()} is the one call made from another thread.
 */
public final class AssignedTopicPoller<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssignedTopicPoller.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    public interface RecordHandler<K, V> {
        void handle(ConsumerRecord<K, V> record);
    }

    private final String name;
    private final String topic;
    private final Supplier<Consumer<K, V>> consumerFactory;
    private final RecordHandler<K, V> handler;
    private final Duration pollTimeout;
    private final Duration replayTimeout;

    /**
     * A request to be told once the poller has handled everything the topic held when the request was made.
     * The end offsets are snapshotted on the poller thread, the first time it sees the request.
     */
    private static final class Barrier {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private Map<TopicPartition, Long> endOffsets;
    }

    private final CompletableFuture<Void> replayed = new CompletableFuture<>();
    private final List<Barrier> barriers = new ArrayList<>();
    private final Object pauseLock = new Object();
    private boolean paused;
    private boolean parked;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Consumer<K, V> consumer;

    public AssignedTopicPoller(String name, String topic, Supplier<Consumer<K, V>> consumerFactory,
                               RecordHandler<K, V> handler, Duration pollTimeout, Duration replayTimeout) {
        this.name = name;
        this.topic = topic;
        this.consumerFactory = consumerFactory;
        this.handler = handler;
        this.pollTimeout = pollTimeout;
        this.replayTimeout = replayTimeout;
    }

    /**
     * Start the poller thread and wait for the replay of the topic to complete.
     *
     * @throws IllegalStateException when the topic does not exist or the replay does not complete within the
     *                               replay timeout.
     */
    public void start() {
        if (running) {
            throw new IllegalStateException(name + " is already started");
        }
        running = true;
        Thread t = new Thread(this::run, name + "-poller");
        t.setDaemon(true);
        thread = t;
        t.start();
        try {
            replayed.get(replayTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            stop(CLOSE_TIMEOUT);
            throw new IllegalStateException("Replay of topic " + topic + " did not complete within " + replayTimeout);
        } catch (ExecutionException e) {
            stop(CLOSE_TIMEOUT);
            throw new IllegalStateException("Replay of topic " + topic + " failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(CLOSE_TIMEOUT);
            throw new IllegalStateException("Interrupted while replaying topic " + topic, e);
        }
    }

    /**
     * Stop the poller thread and close the consumer.
     */
    public void stop(Duration timeout) {
        running = false;
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        Consumer<K, V> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(timeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isAlive() {
        Thread t = thread;
        return running && t != null && t.isAlive();
    }

    /**
     * @return a future completed once every record the topic held at the time of this call has been handled,
     * so that a caller can conclude that a record it has not seen by then was never written; failed when the
     * poller stops first. Checked once per poll, so it completes at the earliest one poll timeout later.
     */
    public CompletableFuture<Void> caughtUp() {
        Barrier barrier = new Barrier();
        synchronized (barriers) {
            if (!running) {
                barrier.done.completeExceptionally(new IllegalStateException(name + " is not running"));
            } else {
                barriers.add(barrier);
            }
        }
        return barrier.done;
    }

    /**
     * Test hook: stop calling poll() until {@link #resume()}. Returns once the poller thread is parked, so a
     * record produced after this call is not applied before {@link #resume()}.
     */
    void pause() {
        synchronized (pauseLock) {
            paused = true;
            while (running && !parked) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    private void run() {
        Consumer<K, V> c;
        try {
            c = consumerFactory.get();
        } catch (RuntimeException e) {
            replayed.completeExceptionally(e);
            return;
        }
        consumer = c;
        try {
            if (!running) {
                return;
            }
            replay(c);
            replayed.complete(null);
            LOGGER.debug("{}: following topic {}", name, topic);
            while (running) {
                awaitUnpaused();
                if (!running) {
                    break;
                }
                try {
                    pollOnce(c);
                    settleBarriers(c);
                } catch (WakeupException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // as Camel's TopicPoller: log and keep following, after a pause to avoid a tight loop
                    LOGGER.warn("{}: error polling topic {}: {}. This exception is ignored.", name, topic, e.toString(), e);
                    backOff();
                }
            }
        } catch (WakeupException e) {
            LOGGER.debug("{}: woken up, stopping", name);
        } catch (RuntimeException | Error e) {
            LOGGER.error("{}: poller of topic {} died", name, topic, e);
            throw e;
        } finally {
            running = false;
            if (!replayed.isDone()) {
                replayed.completeExceptionally(new IllegalStateException(name + ": the poller of topic " + topic + " stopped before the replay completed"));
            }
            failBarriers();
            try {
                c.close(CLOSE_TIMEOUT);
            } catch (RuntimeException e) {
                LOGGER.warn("{}: error closing the consumer of topic {}", name, topic, e);
            }
            LOGGER.info("{}: stopped following topic {}", name, topic);
        }
    }

    private void replay(Consumer<K, V> c) {
        List<PartitionInfo> partitionInfos = c.partitionsFor(topic);
        if (partitionInfos == null || partitionInfos.isEmpty()) {
            throw new IllegalStateException("Topic " + topic + " does not exist");
        }
        List<TopicPartition> partitions = partitionInfos.stream()
                .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                .toList();
        c.assign(partitions);
        c.seekToBeginning(partitions);
        Map<TopicPartition, Long> endOffsets = c.endOffsets(partitions);
        LOGGER.info("{}: replaying topic {} from the beginning, end offsets {}", name, topic, endOffsets);
        long records = 0;
        while (!reached(c, endOffsets)) {
            records += pollOnce(c);
        }
        LOGGER.info("{}: replayed {} records of topic {}", name, records, topic);
    }

    /**
     * Snapshots the end offsets of a new barrier and completes every barrier whose end offsets are reached.
     * Runs on the poller thread, between polls, so a barrier is settled only after the records before its
     * end offsets have been handled.
     */
    private void settleBarriers(Consumer<K, V> c) {
        List<Barrier> pending;
        synchronized (barriers) {
            if (barriers.isEmpty()) {
                return;
            }
            pending = new ArrayList<>(barriers);
        }
        for (Barrier barrier : pending) {
            if (barrier.endOffsets == null) {
                barrier.endOffsets = c.endOffsets(c.assignment());
            }
            if (reached(c, barrier.endOffsets)) {
                synchronized (barriers) {
                    barriers.remove(barrier);
                }
                barrier.done.complete(null);
            }
        }
    }

    private void failBarriers() {
        List<Barrier> pending;
        synchronized (barriers) {
            pending = new ArrayList<>(barriers);
            barriers.clear();
        }
        for (Barrier barrier : pending) {
            barrier.done.completeExceptionally(new IllegalStateException(name + ": the poller of topic " + topic + " stopped"));
        }
    }

    private static <K, V> boolean reached(Consumer<K, V> c, Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (c.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private int pollOnce(Consumer<K, V> c) {
        ConsumerRecords<K, V> records;
        try {
            records = c.poll(pollTimeout);
        } catch (RecordDeserializationException e) {
            LOGGER.error("{}: cannot deserialize record at [topic:{}, partition:{}, offset:{}]. Skipping it.",
                    name, e.topicPartition().topic(), e.topicPartition().partition(), e.offset(), e);
            c.seek(e.topicPartition(), e.offset() + 1);
            return 0;
        }
        for (ConsumerRecord<K, V> record : records) {
            try {
                handler.handle(record);
            } catch (RuntimeException e) {
                LOGGER.error("{}: error handling record at [topic:{}, partition:{}, offset:{}]. Skipping it.",
                        name, record.topic(), record.partition(), record.offset(), e);
            }
        }
        return records.count();
    }

    private void backOff() {
        synchronized (pauseLock) {
            if (running) {
                try {
                    pauseLock.wait(pollTimeout.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void awaitUnpaused() {
        synchronized (pauseLock) {
            while (paused && running) {
                parked = true;
                pauseLock.notifyAll();
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            parked = false;
        }
    }
}
