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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.event.NetexImportEventFactory;
import no.entur.nisaba.idempotent.IdempotentRepository;
import no.entur.nisaba.kafka.NetexImportEventPublisher;
import no.entur.nisaba.pubsub.NetexExportNotification;
import no.entur.nisaba.services.MardukBlobStoreService;
import no.entur.nisaba.services.NisabaExchangeBlobStoreService;
import org.junit.jupiter.api.Test;
import org.rutebanken.helper.storage.repository.InMemoryBlobStoreRepository;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision tree of one notification, with hand-written doubles.
 */
class NetexImportPipelineTest {

    static final class RecordingClaims implements IdempotentRepository {
        final Map<String, String> owners = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<Claim> claim(String importKey) {
            calls.add("claim " + importKey);
            if (owners.containsKey(importKey)) {
                return Optional.empty();
            }
            owners.put(importKey, "c-" + owners.size());
            return Optional.of(new Claim(importKey, owners.get(importKey), owners.size()));
        }

        @Override
        public void confirm(Claim claim) {
            calls.add("confirm " + claim.importKey());
        }

        @Override
        public void release(Claim claim) {
            calls.add("release " + claim.importKey());
            owners.remove(claim.importKey());
        }
    }

    static final class RecordingPublisher implements NetexImportEventPublisher {
        final List<NetexImportEvent> events = new ArrayList<>();
        RuntimeException failure;

        @Override
        public void publish(NetexImportEvent event) {
            if (failure != null) {
                throw failure;
            }
            events.add(event);
        }
    }

    private final InMemoryBlobStoreRepository marduk = new InMemoryBlobStoreRepository(new HashMap<>());
    private final InMemoryBlobStoreRepository exchange = new InMemoryBlobStoreRepository(new HashMap<>());
    private final RecordingClaims claims = new RecordingClaims();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NetexImportPipeline pipeline = new NetexImportPipeline(
            new MardukBlobStoreService("marduk", marduk),
            new DatasetCreationTimeExtractor(),
            new ChouetteImportKeyResolver(new NisabaExchangeBlobStoreService("nisaba-exchange", exchange), "nisaba-exchange"),
            new NetexImportEventFactory("marduk"),
            claims, publisher, new NetexImportMetrics(registry), "rutedata-dataset-import-event-dev");

    private void publishedDataset() {
        marduk.uploadBlob("outbound/netex/rb_avi-aggregated-netex.zip",
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip"));
    }

    private double count(String outcome) {
        return registry.get(NetexImportMetrics.NOTIFICATIONS).tag("outcome", outcome).counter().count();
    }

    @Test
    void newDatasetIsClaimedPublishedAndConfirmed() {
        publishedDataset();
        exchange.uploadBlob("imported/avi/avi_2021-04-05T09_09_45.409.zip", new ByteArrayInputStream("x".getBytes()));

        pipeline.process(new NetexExportNotification("avi", "corr-1"));

        assertEquals(List.of("claim avi_2021-04-13T09_09_45.409", "confirm avi_2021-04-13T09_09_45.409"), claims.calls);
        assertEquals(1, publisher.events.size());
        NetexImportEvent event = publisher.events.getFirst();
        assertEquals("avi", event.getCodespace().toString());
        assertEquals("2021-04-13T09:09:45.409", event.getImportDateTime().toString());
        assertEquals("avi_2021-04-13T09_09_45.409", event.getImportKey().toString());
        assertEquals("gs://marduk/outbound/netex/rb_avi-aggregated-netex.zip", event.getPublishedDatasetURI().toString());
        assertEquals("https://storage.googleapis.com/marduk/outbound/netex/rb_avi-aggregated-netex.zip", event.getPublishedDatasetPublicLink().toString());
        assertEquals("imported/avi/avi_2021-04-05T09_09_45.409.zip", event.getOriginalDatasetURI().toString());
        assertEquals(1, count("published"));
    }

    @Test
    void secondNotificationForTheSameDatasetIsSkipped() {
        publishedDataset();
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        pipeline.process(new NetexExportNotification("avi", "corr-2"));
        assertEquals(1, publisher.events.size());
        assertEquals(1, count("duplicate"));
        assertEquals(List.of("claim avi_2021-04-13T09_09_45.409", "confirm avi_2021-04-13T09_09_45.409", "claim avi_2021-04-13T09_09_45.409"), claims.calls);
    }

    @Test
    void missingDatasetIsLoggedAndNothingIsClaimed() {
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        assertTrue(claims.calls.isEmpty());
        assertTrue(publisher.events.isEmpty());
        assertEquals(1, count("dataset_missing"));
    }

    @Test
    void publishFailureReleasesTheClaimAndPropagates() {
        publishedDataset();
        publisher.failure = new IllegalStateException("kafka down");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> pipeline.process(new NetexExportNotification("avi", "corr-1")));
        assertEquals("kafka down", e.getMessage());
        assertEquals(List.of("claim avi_2021-04-13T09_09_45.409", "release avi_2021-04-13T09_09_45.409"), claims.calls);

        publisher.failure = null;
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        assertEquals(1, publisher.events.size());
    }

    @Test
    void originalDatasetUriIsEmptyWhenNoChouetteImportExists() {
        publishedDataset();
        pipeline.process(new NetexExportNotification("avi", "corr-1"));
        assertEquals("", publisher.events.getFirst().getOriginalDatasetURI().toString());
    }
}
