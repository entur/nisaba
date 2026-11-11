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

package no.entur.nisaba.pubsub;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetexExportNotificationTest {

    @Test
    void codespaceIsTheBody() {
        NetexExportNotification n = NetexExportNotification.from("avi".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals("avi", n.codespace());
        assertFalse(n.correlationId().isBlank());
    }

    @Test
    void correlationIdAttributeIsHonoured() {
        NetexExportNotification n = NetexExportNotification.from("avi".getBytes(StandardCharsets.UTF_8),
                Map.of("RutebankenCorrelationId", "from-marduk"));
        assertEquals("from-marduk", n.correlationId());
    }

    @Test
    void freshIdWhenTheAttributeIsAbsentOrBlank() {
        NetexExportNotification a = NetexExportNotification.from("avi".getBytes(StandardCharsets.UTF_8), null);
        NetexExportNotification b = NetexExportNotification.from("avi".getBytes(StandardCharsets.UTF_8), Map.of("RutebankenCorrelationId", " "));
        assertNotEquals(a.correlationId(), b.correlationId());
    }

    @Test
    void bodyIsTrimmedAndBlankIsRejected() {
        assertEquals("avi", NetexExportNotification.from(" avi\n".getBytes(StandardCharsets.UTF_8), Map.of()).codespace());
        assertThrows(IllegalArgumentException.class, () -> NetexExportNotification.from(" ".getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> NetexExportNotification.from(null, Map.of()));
    }
}
