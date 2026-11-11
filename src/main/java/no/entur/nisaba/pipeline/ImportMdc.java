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

import org.slf4j.MDC;

import java.util.Objects;

/**
 * The identity of the notification being processed, for logging.
 * <p>
 * Replaces Camel's MDC and the {@code [codespace=... correlationId=...] } prefix every route log line carried;
 * the prefix is kept verbatim so that saved log queries keep working.
 */
public final class ImportMdc {

    public static final String CODESPACE = "codespace";
    public static final String CORRELATION_ID = "correlationId";

    private ImportMdc() {
    }

    /**
     * Cleared on entry to the message handler rather than in a finally block, so that the consumer's own
     * error line, logged after the handler returns, still carries the identity.
     */
    public static void clear() {
        MDC.remove(CODESPACE);
        MDC.remove(CORRELATION_ID);
    }

    public static void put(String codespace, String correlationId) {
        MDC.put(CODESPACE, codespace);
        MDC.put(CORRELATION_ID, correlationId);
    }

    /**
     * @return the log line prefix the Camel routes used, from the current MDC.
     */
    public static String prefix() {
        return prefix(MDC.get(CODESPACE), MDC.get(CORRELATION_ID));
    }

    public static String prefix(String codespace, String correlationId) {
        return "[codespace=" + Objects.requireNonNullElse(codespace, "")
                + " correlationId=" + Objects.requireNonNullElse(correlationId, "") + "] ";
    }
}
