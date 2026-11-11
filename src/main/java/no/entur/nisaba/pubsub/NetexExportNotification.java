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

import no.entur.nisaba.Constants;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * A NeTEx export notification as Marduk publishes it: the codespace in the body, nothing else.
 *
 * @param codespace     the codespace of the exported dataset, for example {@code avi}.
 * @param correlationId Marduk's {@code RutebankenCorrelationId} attribute when present, else a fresh UUID.
 */
public record NetexExportNotification(String codespace, String correlationId) {

    public static NetexExportNotification from(byte[] body, Map<String, String> attributes) {
        String codespace = body == null ? "" : new String(body, StandardCharsets.UTF_8).trim();
        if (codespace.isEmpty()) {
            throw new IllegalArgumentException("The NeTEx export notification carries no codespace");
        }
        String correlationId = attributes == null ? null : attributes.get(Constants.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return new NetexExportNotification(codespace, correlationId);
    }
}
