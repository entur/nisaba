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

package no.entur.nisaba.kafka;

import no.entur.nisaba.avro.NetexImportEvent;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the events instead of publishing them, together with the MDC of the publishing thread.
 */
public class RecordingNetexImportEventPublisher implements NetexImportEventPublisher {

    public record Published(NetexImportEvent event, Map<String, String> mdc) {
    }

    private final List<Published> published = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    @Override
    public void publish(NetexImportEvent event) {
        RuntimeException f = failure;
        if (f != null) {
            throw f;
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        published.add(new Published(event, mdc == null ? Map.of() : Map.copyOf(mdc)));
    }

    public List<Published> published() {
        return published;
    }

    public List<NetexImportEvent> events() {
        return published.stream().map(Published::event).toList();
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void clear() {
        published.clear();
        failure = null;
    }
}
