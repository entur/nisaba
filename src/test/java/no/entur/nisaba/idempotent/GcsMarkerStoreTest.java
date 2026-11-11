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

package no.entur.nisaba.idempotent;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import no.entur.nisaba.idempotent.MarkerStore.Marker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GCS calls of the marker store, against a GCS emulator (needs Docker).
 * <p>
 * The emulator enforces the precondition of the create, which is what makes a claim atomic, but ignores the
 * generation precondition of the delete and of the metadata patch: those two are pinned at the HTTP level by
 * {@link GcsMarkerStoreRequestTest}.
 */
class GcsMarkerStoreTest {

    private static final String BUCKET = "nisaba-idempotent-repo";

    private static GenericContainer<?> gcs;
    private static Storage storage;
    private static GcsMarkerStore store;

    @BeforeAll
    static void startEmulator() {
        gcs = new GenericContainer<>(DockerImageName.parse("fsouza/fake-gcs-server:1.52.2"))
                .withExposedPorts(4443)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/fake-gcs-server", "-scheme", "http", "-backend", "memory"));
        gcs.start();
        storage = StorageOptions.newBuilder()
                .setHost("http://" + gcs.getHost() + ":" + gcs.getMappedPort(4443))
                .setProjectId("test")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        storage.create(BucketInfo.of(BUCKET));
        store = new GcsMarkerStore(storage, BUCKET);
    }

    @AfterAll
    static void stopEmulator() throws Exception {
        if (storage != null) {
            storage.close();
        }
        if (gcs != null) {
            gcs.stop();
        }
    }

    @Test
    void firstCreateWinsAndTheSecondLeavesTheObjectUntouched() {
        String key = "avi_2021-04-21T11_51_59.123";
        OptionalLong first = store.createIfAbsent(key, "a:1", false);
        assertTrue(first.isPresent());
        assertTrue(store.createIfAbsent(key, "b:2", true).isEmpty());

        Marker marker = store.read(key).orElseThrow();
        assertEquals(first.getAsLong(), marker.generation());
        assertEquals("a:1", marker.claimId());
        assertFalse(marker.confirmed());
        assertTrue(Duration.between(marker.createdAt(), Instant.now()).abs().compareTo(Duration.ofMinutes(5)) < 0,
                "created at " + marker.createdAt());

        // the wire format: what gsutil cat and gsutil stat show
        Blob blob = storage.get(BlobId.of(BUCKET, key));
        assertArrayEquals("a:1".getBytes(StandardCharsets.UTF_8), blob.getContent());
        assertEquals("a:1", blob.getMetadata().get("claim-id"));
        assertFalse(blob.getMetadata().containsKey("confirmed"));
    }

    @Test
    void absentKeyReadsAsEmpty() {
        assertTrue(store.read("absent").isEmpty());
    }

    @Test
    void confirmKeepsTheGenerationAndTheClaimId() {
        String key = "confirm";
        long generation = store.createIfAbsent(key, "a:1", false).orElseThrow();
        assertTrue(store.confirm(key, generation));
        Marker marker = store.read(key).orElseThrow();
        assertTrue(marker.confirmed());
        assertEquals("a:1", marker.claimId());
        assertEquals(generation, marker.generation());
        assertEquals("true", storage.get(BlobId.of(BUCKET, key)).getMetadata().get("confirmed"));
    }

    @Test
    void markerCreatedConfirmedReadsAsConfirmed() {
        store.createIfAbsent("migrated", "migrated:camel", true).orElseThrow();
        assertTrue(store.read("migrated").orElseThrow().confirmed());
    }

    @Test
    void deletedKeyCanBeCreatedAgainWithANewGeneration() {
        String key = "generation";
        long old = store.createIfAbsent(key, "a:1", false).orElseThrow();
        assertTrue(store.deleteIfGeneration(key, old));
        assertTrue(store.read(key).isEmpty());
        long current = store.createIfAbsent(key, "b:2", false).orElseThrow();
        assertTrue(current != old);
        assertEquals("b:2", store.read(key).orElseThrow().claimId());
    }

    @Test
    void confirmAndDeleteOfAnAbsentKeyDoNothing() {
        assertFalse(store.confirm("never", 1));
        assertFalse(store.deleteIfGeneration("never", 1));
    }

    @Test
    void oneOfManyConcurrentCreatesWins() throws Exception {
        int claimers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(claimers);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<OptionalLong>> futures = new ArrayList<>();
            for (int i = 0; i < claimers; i++) {
                String claimId = "pod" + i + ":1";
                futures.add(executor.submit(() -> {
                    go.await();
                    return store.createIfAbsent("race", claimId, false);
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<OptionalLong> future : futures) {
                if (future.get().isPresent()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void accessCheckFailsOnAMissingBucket() {
        store.checkAccess();
        GcsMarkerStore missing = new GcsMarkerStore(storage, "no-such-bucket");
        assertThrows(IdempotentRepositoryException.class, missing::checkAccess);
        assertThrows(IllegalArgumentException.class, () -> new GcsMarkerStore(storage, " "));
    }

    @Test
    void locationIsTheGsutilUri() {
        assertEquals("gs://" + BUCKET + "/k", store.location("k"));
    }
}
