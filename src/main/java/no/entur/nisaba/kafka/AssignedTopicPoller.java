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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
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
 * A record that cannot be deserialized is skipped only when the record itself is at fault. When the schema
 * registry cannot be reached or refuses the request, the record is read again until it can be: skipping would
 * drop every record of the topic while the registry is down, and a replay that "completes" that way leaves the
 * pod ready with nothing read.
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

    /**
     * The consumer settings of a poller: every partition assigned by hand and read from the beginning, so no
     * consumer group, no committed offsets and no topic creation.
     *
     * @param config the common Kafka client settings (brokers, security, schema registry).
     */
    public static Map<String, Object> consumerProperties(Map<String, Object> config, String clientId,
                                                         Class<? extends Deserializer<?>> keyDeserializer,
                                                         Class<? extends Deserializer<?>> valueDeserializer) {
        Map<String, Object> props = new HashMap<>(config);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        props.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        return props;
    }

    private final String name;
    private final String topic;
    private final Supplier<Consumer<K, V>> consumerFactory;
    private final RecordHandler<K, V> handler;
    private final Duration pollTimeout;
    private final Duration replayTimeout;

    private final CompletableFuture<Void> replayed = new CompletableFuture<>();
    private final Object stopLock = new Object();
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Consumer<K, V> consumer;
    private volatile RuntimeException lastReplayFailure;

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
            RuntimeException failure = lastReplayFailure;
            throw new IllegalStateException("Replay of topic " + topic + " did not complete within " + replayTimeout
                    + (failure == null ? "" : ": " + failure.getMessage() + " (" + failure.getCause() + ")"), failure);
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
        synchronized (stopLock) {
            stopLock.notifyAll();
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
                try {
                    pollOnce(c);
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
            try {
                records += pollOnce(c);
            } catch (RecordDeserializationException e) {
                // not the record's fault: read it again until the replay timeout fails the start
                lastReplayFailure = e;
                LOGGER.warn("{}: cannot deserialize record at [topic:{}, partition:{}, offset:{}]: {}. Trying again.",
                        name, e.topicPartition().topic(), e.topicPartition().partition(), e.offset(), String.valueOf(e.getCause()));
                backOff();
            }
        }
        LOGGER.info("{}: replayed {} records of topic {}", name, records, topic);
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
            if (!isRecordAtFault(e)) {
                // the position stays on the record: the next poll reads it again
                throw e;
            }
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

    /**
     * Tells a record that can never be read from a schema registry that cannot answer now. The Confluent
     * deserializer reports the registry's refusals (401, 403, 429, 5xx) as Kafka {@link ApiException}s and a
     * connection failure as a {@link SerializationException} "Error retrieving ... schema" caused by an
     * {@link IOException}; anything else (unknown magic byte, unknown schema id, undecodable payload) is the
     * record's fault.
     */
    static boolean isRecordAtFault(RecordDeserializationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApiException) {
            return false;
        }
        return !(cause instanceof SerializationException && cause.getCause() instanceof IOException
                && cause.getMessage() != null && cause.getMessage().startsWith("Error retrieving"));
    }

    private void backOff() {
        synchronized (stopLock) {
            if (running) {
                try {
                    stopLock.wait(pollTimeout.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
