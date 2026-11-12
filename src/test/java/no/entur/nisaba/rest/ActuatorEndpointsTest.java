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

package no.entur.nisaba.rest;

import no.entur.nisaba.TestApp;
import no.entur.nisaba.kafka.KafkaTestDoubles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actuator endpoints the deployment depends on (the liveness and readiness probes, the Prometheus scrape),
 * served under the {@code management.*} properties of the Helm ConfigMap: no access by default, then the three
 * endpoints enabled one by one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TestApp.class, properties = {
        "management.endpoints.access.default=none",
        "management.endpoint.info.enabled=true",
        "management.endpoint.health.enabled=true",
        "management.endpoint.health.group.readiness.include=readinessState",
        "management.endpoint.prometheus.enabled=true",
        "management.endpoints.web.exposure.include=info,health,prometheus",
        "management.endpoints.web.exposure.exclude=",
        "management.health.pubsub.enabled=false",
})
@ActiveProfiles({"test", "in-memory-blobstore"})
@Import(KafkaTestDoubles.class)
class ActuatorEndpointsTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void theProbesAnswerUp() throws IOException, InterruptedException {
        for (String probe : new String[]{"liveness", "readiness"}) {
            HttpResponse<String> response = get("/actuator/health/" + probe);
            assertEquals(200, response.statusCode(), probe);
            assertTrue(response.body().contains("\"UP\""), response.body());
        }
    }

    @Test
    void prometheusIsScrapable() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/actuator/prometheus");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("jvm_memory_used_bytes"), "JVM metrics are exported");
    }

    @Test
    void anEndpointThatIsNotEnabledIsNotServed() throws IOException, InterruptedException {
        assertEquals(404, get("/actuator/env").statusCode());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
