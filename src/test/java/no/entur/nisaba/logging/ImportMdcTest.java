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

package no.entur.nisaba.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImportMdcTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void prefixHasTheCamelRouteFormat() {
        ImportMdc.put("avi", "corr-1");
        assertEquals("[codespace=avi correlationId=corr-1] ", ImportMdc.prefix());
        assertEquals("avi", MDC.get("codespace"));
        assertEquals("corr-1", MDC.get("correlationId"));
    }

    @Test
    void missingValuesRenderEmptyLikeCamelSimpleDid() {
        ImportMdc.clear();
        assertEquals("[codespace= correlationId=] ", ImportMdc.prefix());
        assertEquals("[codespace=avi correlationId=] ", ImportMdc.prefix("avi", null));
    }

    @Test
    void clearRemovesBothKeys() {
        ImportMdc.put("avi", "corr-1");
        ImportMdc.clear();
        assertNull(MDC.get("codespace"));
        assertNull(MDC.get("correlationId"));
    }
}
