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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetexImportEventKeyFactoryTest {

    @Test
    void colonsBecomeUnderscores() {
        assertEquals("avi_2021-04-21T11_51_59", NetexImportEventKeyFactory.createNetexImportEventKey("avi", LocalDateTime.parse("2021-04-21T11:51:59")));
        assertEquals("avi_2021-04-21T11_51_59.123", NetexImportEventKeyFactory.createNetexImportEventKey("avi", LocalDateTime.parse("2021-04-21T11:51:59.123")));
        assertEquals("avi_2021-04-21T11_51_59.12", NetexImportEventKeyFactory.createNetexImportEventKey("avi", LocalDateTime.parse("2021-04-21T11:51:59.120")));
    }
}
