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

package no.entur.nisaba.pipeline;

import no.entur.nisaba.TestApp;
import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.idempotent.InMemoryMarkerStore;
import no.entur.nisaba.kafka.KafkaTestDoubles;
import no.entur.nisaba.kafka.RecordingNetexImportEventPublisher;
import no.entur.nisaba.logging.ImportMdc;
import no.entur.nisaba.pubsub.NetexExportNotification;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline as Spring wires it, against the in-memory blob store, the in-memory marker store and a test double for the Kafka publisher: no
 * emulator, no broker, no credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestApp.class)
@ActiveProfiles({"test", "in-memory-blobstore"})
@Import(KafkaTestDoubles.class)
class NetexImportPipelineSpringTest {

    @Autowired
    private NetexImportPipeline pipeline;

    @Autowired
    private Map<String, Map<String, byte[]>> blobsInContainers;

    private static final String IMPORT_KEY = "avi_2021-04-13T09_09_45.409";

    @Autowired
    private InMemoryMarkerStore claims;

    @Autowired
    private RecordingNetexImportEventPublisher publisher;

    @BeforeEach
    void publishedDataset() throws IOException {
        blobsInContainers.clear();
        claims.read(IMPORT_KEY).ifPresent(marker -> claims.deleteIfGeneration(IMPORT_KEY, marker.generation()));
        publisher.clear();
        byte[] zip = getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip").readAllBytes();
        blobsInContainers.computeIfAbsent("marduk", k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put("outbound/netex/rb_avi-aggregated-netex.zip", zip);
        blobsInContainers.computeIfAbsent("nisaba-exchange", k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put("imported/avi/avi_2021-04-05T09_09_45.409.zip", "x".getBytes());
    }

    @AfterEach
    void clearMdc() {
        ImportMdc.clear();
    }

    @Test
    void oneEventPerDataset() {
        ImportMdc.put("avi", "corr-1");
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        pipeline.process(new NetexExportNotification("avi", "corr-2"));

        List<NetexImportEvent> events = publisher.events();
        assertEquals(1, events.size());
        NetexImportEvent event = events.getFirst();
        assertEquals("avi", event.getCodespace().toString());
        assertEquals("2021-04-13T09:09:45.409", event.getImportDateTime().toString());
        assertEquals("avi_2021-04-13T09_09_45.409", event.getImportKey().toString());
        assertEquals("gs://marduk/outbound/netex/rb_avi-aggregated-netex.zip", event.getPublishedDatasetURI().toString());
        assertEquals("imported/avi/avi_2021-04-05T09_09_45.409.zip", event.getOriginalDatasetURI().toString());
        assertEquals("corr-1", publisher.published().getFirst().mdc().get("correlationId"));
        assertTrue(claims.read(IMPORT_KEY).isPresent());
    }

    @Test
    void publishFailureReleasesTheClaimSoTheRetryPublishes() {
        publisher.failWith(new IllegalStateException("kafka down"));
        assertThrows(IllegalStateException.class, () -> pipeline.process(new NetexExportNotification("avi", "corr-1")));
        assertFalse(claims.read(IMPORT_KEY).isPresent());

        publisher.clear();
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        assertEquals(1, publisher.events().size());
    }

    @Test
    void missingDatasetPublishesNothing() {
        blobsInContainers.get("marduk").clear();
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        assertTrue(publisher.events().isEmpty());
        assertFalse(claims.read(IMPORT_KEY).isPresent());
    }

    /**
     * The Avro class security validator must be installed by the application context itself
     * (AvroClassSecurityConfig). Without it the Kafka clients cannot serialize or deserialize any event.
     */
    @Test
    void avroClassSecurityValidatorIsInstalledAtStartup() {
        assertNotSame(ClassSecurityValidator.DEFAULT, ClassSecurityValidator.getGlobal());
        assertEquals(NetexImportEvent.class, new SpecificData().getClass(NetexImportEvent.getClassSchema()));
    }
}
