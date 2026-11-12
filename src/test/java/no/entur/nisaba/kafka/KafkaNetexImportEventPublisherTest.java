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
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the publisher reports when a send fails: the event may be on the topic all the same.
 */
class KafkaNetexImportEventPublisherTest {

    private static final String TOPIC = "rutedata-dataset-import-event-test";
    private static final String KEY = "avi_2021-04-21T11_51_59";
    private static final NetexImportEvent EVENT = ImportDateMapReaderTest.event("avi", LocalDateTime.of(2021, 4, 21, 11, 51, 59));
    private static final Serializer<NetexImportEvent> VALUE_SERIALIZER = (topic, event) -> new byte[0];

    private final PublishedImportKeys publishedImportKeys = new PublishedImportKeys();
    private MockProducer<String, NetexImportEvent> producer = new MockProducer<>(false, null, new StringSerializer(), VALUE_SERIALIZER);
    private KafkaNetexImportEventPublisher publisher = publisher(producer, Duration.ofMillis(50));

    private KafkaNetexImportEventPublisher publisher(MockProducer<String, NetexImportEvent> mock, Duration verificationTimeout) {
        KafkaNetexImportEventPublisher p = new KafkaNetexImportEventPublisher(TOPIC, () -> mock, Duration.ofSeconds(5),
                publishedImportKeys, verificationTimeout);
        p.start();
        return p;
    }

    @AfterEach
    void stop() {
        publisher.stop();
    }

    /** Runs the publish while the test completes the send from outside, as the producer's I/O thread would. */
    private CompletableFuture<Void> publishAsync() throws Exception {
        CompletableFuture<Void> published = CompletableFuture.runAsync(() -> publisher.publish(EVENT));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (producer.history().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, producer.history().size(), "the record was sent");
        return published;
    }

    @Test
    void acknowledgedSendIsPublished() throws Exception {
        CompletableFuture<Void> published = publishAsync();
        assertTrue(producer.completeNext());
        published.get(5, TimeUnit.SECONDS);
    }

    @Test
    void failedSendWhoseEventIsNotOnTheTopicIsReported() throws Exception {
        CompletableFuture<Void> published = publishAsync();
        assertTrue(producer.errorNext(new TimeoutException("Expiring 1 record(s)")));
        Exception e = assertThrows(Exception.class, () -> published.get(5, TimeUnit.SECONDS));
        assertEquals("Failed to publish event " + KEY + " to " + TOPIC, e.getCause().getMessage());
    }

    @Test
    void failedSendWhoseEventIsReadBackIsPublished() throws Exception {
        publisher.stop();
        producer = new MockProducer<>(false, null, new StringSerializer(), VALUE_SERIALIZER);
        publisher = publisher(producer, Duration.ofSeconds(30));
        CompletableFuture<Void> published = publishAsync();
        // the delivery timeout expired although the broker had appended the record
        assertTrue(producer.errorNext(new TimeoutException("Expiring 1 record(s)")));
        assertFalse(published.isDone(), "waiting for the reader");
        publishedImportKeys.seen(KEY, 7);
        published.get(5, TimeUnit.SECONDS);
    }

    @Test
    void earlierEventOfTheSameKeyDoesNotCountAsReadBack() throws Exception {
        // the key was published before, then its marker deleted by hand to publish it again
        publishedImportKeys.seen(KEY, 7);
        CompletableFuture<Void> published = publishAsync();
        assertTrue(producer.errorNext(new TimeoutException("Expiring 1 record(s)")));
        assertThrows(Exception.class, () -> published.get(5, TimeUnit.SECONDS));
    }

    @Test
    void sendThatNeverLeftTheProducerIsReportedAtOnce() {
        publisher.stop();
        MockProducer<String, NetexImportEvent> failing = new MockProducer<>(false, null, new StringSerializer(), (topic, event) -> {
            throw new SerializationException("schema registry down");
        });
        publisher = publisher(failing, Duration.ofSeconds(30));
        long start = System.nanoTime();
        assertThrows(SerializationException.class, () -> publisher.publish(EVENT));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "no wait for a read-back");
    }

    @Test
    void importKeysRememberTheLatestOffsetAndForgetTheOldestKey() {
        PublishedImportKeys keys = new PublishedImportKeys();
        assertEquals(PublishedImportKeys.NEVER, keys.lastOffset("a"));
        keys.seen("a", 5);
        keys.seen("a", 3);
        assertEquals(5, keys.lastOffset("a"));
        assertTrue(keys.awaitBeyond("a", 4, Duration.ZERO));
        assertFalse(keys.awaitBeyond("a", 5, Duration.ofMillis(10)));
        for (int i = 0; i < PublishedImportKeys.CAPACITY; i++) {
            keys.seen("k" + i, i);
        }
        assertEquals(PublishedImportKeys.NEVER, keys.lastOffset("a"));
    }
}
