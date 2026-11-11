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
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preconditions the marker store sends and what it makes of the answers, against a stub of the GCS JSON
 * API: every write carries the generation it is conditioned on, a 412 or a 404 is "not the owner", anything
 * else is a failure.
 */
class GcsMarkerStoreRequestTest {

    private static final String BUCKET = "bucket";
    private static final String OBJECT = "{\"kind\":\"storage#object\",\"bucket\":\"bucket\",\"name\":\"key\",\"generation\":\"42\","
            + "\"metadata\":{\"claim-id\":\"a:1\"},\"timeCreated\":\"2026-11-11T10:00:00.000Z\"}";

    private HttpServer server;
    private Storage storage;
    private GcsMarkerStore store;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    /** The requests, as "METHOD uri", that the stub refuses with a 403 whatever the status. */
    private volatile Predicate<String> forbidden = request -> false;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            requests.add(request);
            int status = forbidden.test(request) ? 403 : this.status;
            boolean gzip = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            try (InputStream in = gzip ? new GZIPInputStream(exchange.getRequestBody()) : exchange.getRequestBody()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = (status == 200 ? OBJECT
                    : "{\"error\":{\"code\":" + status + ",\"message\":\"stub\",\"errors\":[{\"reason\":\"stub\",\"message\":\"stub\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        storage = StorageOptions.newBuilder()
                .setHost("http://localhost:" + server.getAddress().getPort())
                .setProjectId("test")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        store = new GcsMarkerStore(storage, BUCKET);
    }

    @AfterEach
    void stopStub() throws Exception {
        storage.close();
        server.stop(0);
    }

    private String onlyRequest() {
        assertEquals(1, requests.size(), requests.toString());
        return requests.get(0);
    }

    @Test
    void createIsConditionedOnTheObjectNotExisting() {
        assertEquals(42, store.createIfAbsent("key", "a:1", false).orElseThrow());
        String request = onlyRequest();
        assertTrue(request.startsWith("POST /upload/storage/v1/b/bucket/o?"), request);
        assertTrue(request.contains("ifGenerationMatch=0"), request);
        assertTrue(bodies.get(0).contains("\"claim-id\":\"a:1\""), bodies.get(0));
        assertFalse(bodies.get(0).contains("confirmed"), bodies.get(0));
    }

    @Test
    void createOfAnExistingKeyIsEmpty() {
        status = 412;
        assertTrue(store.createIfAbsent("key", "a:1", false).isEmpty());
        onlyRequest();
    }

    @Test
    void deleteIsConditionedOnTheGeneration() {
        assertTrue(store.deleteIfGeneration("key", 42));
        String request = onlyRequest();
        assertTrue(request.startsWith("DELETE /storage/v1/b/bucket/o/key?"), request);
        assertTrue(request.contains("ifGenerationMatch=42"), request);
    }

    @Test
    void deleteOfAnotherGenerationOrOfAnAbsentKeyIsFalse() {
        status = 412;
        assertFalse(store.deleteIfGeneration("key", 41));
        status = 404;
        assertFalse(store.deleteIfGeneration("key", 41));
        assertEquals(2, requests.size());
    }

    @Test
    void confirmPatchesTheMetadataOfThatGenerationOnly() {
        assertTrue(store.confirm("key", 42));
        String request = onlyRequest();
        assertTrue(request.startsWith("PATCH /storage/v1/b/bucket/o/key?") || request.startsWith("POST /storage/v1/b/bucket/o/key?"), request);
        assertTrue(request.contains("ifGenerationMatch=42"), request);
        assertTrue(bodies.get(0).contains("\"confirmed\":\"true\""), bodies.get(0));
        assertFalse(bodies.get(0).contains("claim-id"), "a patch: the claim id is not rewritten: " + bodies.get(0));
    }

    @Test
    void confirmOfAnotherGenerationOrOfAnAbsentKeyIsFalse() {
        status = 412;
        assertFalse(store.confirm("key", 41));
        status = 404;
        assertFalse(store.confirm("key", 41));
        assertEquals(2, requests.size());
    }

    @Test
    void otherFailuresAreReported() {
        status = 403;
        assertThrows(IdempotentRepositoryException.class, () -> store.createIfAbsent("key", "a:1", false));
        assertThrows(IdempotentRepositoryException.class, () -> store.read("key"));
        assertThrows(IdempotentRepositoryException.class, () -> store.confirm("key", 42));
        assertThrows(IdempotentRepositoryException.class, () -> store.deleteIfGeneration("key", 42));
        assertThrows(IdempotentRepositoryException.class, store::checkAccess);
    }

    @Test
    void accessCheckCreatesConfirmsAndDeletesAProbe() {
        store.checkAccess();
        assertEquals(3, requests.size(), requests.toString());
        assertTrue(requests.get(0).startsWith("POST /upload/storage/v1/b/bucket/o?"), requests.get(0));
        assertTrue(requests.get(0).contains("ifGenerationMatch=0"), requests.get(0));
        assertTrue(bodies.get(0).contains("\"name\":\"" + GcsMarkerStore.ACCESS_CHECK_PREFIX), bodies.get(0));
        assertTrue(bodies.get(1).contains("\"confirmed\":\"true\""), bodies.get(1));
        assertTrue(requests.get(2).startsWith("DELETE /storage/v1/b/bucket/o/"), requests.get(2));
        assertTrue(requests.get(2).contains("ifGenerationMatch=42"), requests.get(2));
    }

    @Test
    void accessCheckFailsWhenTheBucketCannotBeWritten() {
        // a read-only role: every write is refused, the first one is enough
        forbidden = request -> !request.startsWith("GET ");
        assertThrows(IdempotentRepositoryException.class, store::checkAccess);
        assertEquals(1, requests.size(), requests.toString());
    }

    @Test
    void accessCheckFailsWhenAMarkerCannotBeConfirmedAndRemovesItsProbe() {
        forbidden = request -> !request.startsWith("POST /upload/") && !request.startsWith("DELETE ");
        assertThrows(IdempotentRepositoryException.class, store::checkAccess);
        assertEquals(3, requests.size(), requests.toString());
        assertTrue(requests.get(2).startsWith("DELETE "), requests.get(2));
    }

    @Test
    void accessCheckFailsWhenAMarkerCannotBeDeleted() {
        forbidden = request -> request.startsWith("DELETE ");
        assertThrows(IdempotentRepositoryException.class, store::checkAccess);
        assertEquals(3, requests.size(), requests.toString());
    }

    @Test
    void readReturnsTheMarkerAndAbsenceIsEmpty() {
        MarkerStore.Marker marker = store.read("key").orElseThrow();
        assertEquals(42, marker.generation());
        assertEquals("a:1", marker.claimId());
        assertFalse(marker.confirmed());
        assertEquals("2026-11-11T10:00:00Z", marker.createdAt().toString());
        status = 404;
        assertTrue(store.read("key").isEmpty());
    }
}
