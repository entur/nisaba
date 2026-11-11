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

package no.entur.nisaba.event;

import no.entur.nisaba.avro.NetexImportEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetexImportEventFactoryTest {

    private final NetexImportEventFactory factory = new NetexImportEventFactory("marduk-dev");

    @Test
    void buildsTheEventWithTheChouetteOriginal() {
        NetexImportEvent event = factory.createNetexImportEvent("avi", LocalDateTime.parse("2021-04-21T11:51:59.123"),
                "avi_2021-04-21T11_51_59.123", "avi_2021-04-20T10_00_00", "outbound/netex/rb_avi-aggregated-netex.zip");
        assertEquals("avi", event.getCodespace().toString());
        assertEquals("2021-04-21T11:51:59.123", event.getImportDateTime().toString());
        assertEquals("avi_2021-04-21T11_51_59.123", event.getImportKey().toString());
        assertEquals("gs://marduk-dev/outbound/netex/rb_avi-aggregated-netex.zip", event.getPublishedDatasetURI().toString());
        assertEquals("https://storage.googleapis.com/marduk-dev/outbound/netex/rb_avi-aggregated-netex.zip", event.getPublishedDatasetPublicLink().toString());
        assertEquals("imported/avi/avi_2021-04-20T10_00_00.zip", event.getOriginalDatasetURI().toString());
        assertEquals(0, event.getServiceJourneys());
        assertEquals(0, event.getCommonFiles());
    }

    @Test
    void originalIsEmptyWithoutChouetteKeyAndWholeSecondsHaveNoFraction() {
        NetexImportEvent event = factory.createNetexImportEvent("avi", LocalDateTime.parse("2021-04-21T11:51:59"),
                "avi_2021-04-21T11_51_59", null, "outbound/netex/rb_avi-aggregated-netex.zip");
        assertEquals("", event.getOriginalDatasetURI().toString());
        assertEquals("2021-04-21T11:51:59", event.getImportDateTime().toString());
    }

    @Test
    void nanosecondsAreTruncatedToMillis() {
        NetexImportEvent event = factory.createNetexImportEvent("avi", LocalDateTime.parse("2026-09-14T04:00:11.657595972"),
                "k", null, "f");
        assertEquals("2026-09-14T04:00:11.657", event.getImportDateTime().toString());
    }

    @Test
    void requiredArgumentsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> factory.createNetexImportEvent(null, LocalDateTime.now(), "k", null, "f"));
        assertThrows(IllegalArgumentException.class, () -> factory.createNetexImportEvent("avi", LocalDateTime.now(), "k", null, null));
    }
}
