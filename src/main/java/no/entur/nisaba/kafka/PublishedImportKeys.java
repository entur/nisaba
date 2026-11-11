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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The import keys the reader of the event topic has seen, with the offset of their latest event. It lets the
 * publisher settle a publish whose outcome is unknown: the event is on the topic when the reader sees one for
 * the key at an offset beyond the one known before the send.
 * <p>
 * An import key always lands on the same partition (the record key is the codespace), so its offsets compare.
 */
public class PublishedImportKeys {

    static final int CAPACITY = 1000;
    static final long NEVER = -1;

    private final Map<String, Long> offsets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized void seen(String importKey, long offset) {
        offsets.merge(importKey, offset, Math::max);
        notifyAll();
    }

    /**
     * @return the offset of the latest event seen for the key, or {@link #NEVER}.
     */
    public synchronized long lastOffset(String importKey) {
        return offsets.getOrDefault(importKey, NEVER);
    }

    /**
     * @return true when an event for the key is seen beyond the offset within the timeout; false when not, or
     * when interrupted.
     */
    public synchronized boolean awaitBeyond(String importKey, long offset, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (offsets.getOrDefault(importKey, NEVER) <= offset) {
            long millis = (deadline - System.nanoTime()) / 1_000_000;
            if (millis <= 0) {
                return false;
            }
            try {
                wait(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
