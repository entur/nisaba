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

package no.entur.nisaba;

import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.event.NetexImportEventKeyFactory;
import no.entur.nisaba.pipeline.ChouetteImportKeyResolver;
import no.entur.nisaba.pipeline.NetexImportPipeline;
import no.entur.nisaba.pubsub.NisabaQueues;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins every string nisaba is matched on from outside this repository, as literals rather than through the
 * constants (which would move together with a rename): Marduk's queue, the blob paths in Marduk's buckets,
 * the import key format consumers correlate on, the Kafka header and the Avro schema.
 */
class WireContractTest {

    @Test
    void pubSubSubscriptionAndAttribute() {
        assertEquals("NetexExportNotificationQueue", NisabaQueues.NETEX_EXPORT_NOTIFICATION_QUEUE);
        assertEquals("RutebankenCorrelationId", Constants.CORRELATION_ID);
    }

    @Test
    void blobStorePaths() {
        assertEquals("outbound/netex/rb_avi-aggregated-netex.zip", NetexImportPipeline.publishedFileName("avi"));
        assertEquals("imported/avi/avi_2021-04-21T11_51_59.zip", ChouetteImportKeyResolver.importedFileName("avi", "avi_2021-04-21T11_51_59"));
        assertEquals("imported/", Constants.BLOBSTORE_PATH_IMPORTED);
        assertEquals("outbound/", Constants.BLOBSTORE_PATH_OUTBOUND);
        assertEquals("aggregated-netex.zip", Constants.CURRENT_AGGREGATED_NETEX_FILENAME);
    }

    @Test
    void importKeyFormat() {
        assertEquals("avi_2021-04-21T11_51_59", NetexImportEventKeyFactory.createNetexImportEventKey("avi", LocalDateTime.of(2021, 4, 21, 11, 51, 59)));
        assertEquals("avi_2021-04-21T11_51_59.123", NetexImportEventKeyFactory.createNetexImportEventKey("avi", LocalDateTime.of(2021, 4, 21, 11, 51, 59, 123_000_000)));
    }

    @Test
    void kafkaHeaderAndAvroSchema() {
        assertEquals("EnturDatasetImportKey", Constants.DATASET_IMPORT_KEY);
        assertEquals("http://www.netex.org.uk/netex", Constants.NETEX_NAMESPACE);
        assertEquals(List.of("codespace", "importDateTime", "importKey", "publishedDatasetURI", "publishedDatasetPublicLink",
                        "originalDatasetURI", "serviceJourneys", "commonFiles"),
                NetexImportEvent.getClassSchema().getFields().stream().map(Schema.Field::name).toList());
        assertEquals("no.entur.nisaba.avro.NetexImportEvent", NetexImportEvent.getClassSchema().getFullName());
    }
}
