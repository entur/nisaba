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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * One counter: how many notifications ended in which outcome.
 */
@Component
public class NetexImportMetrics {

    public static final String NOTIFICATIONS = "nisaba.notifications";

    public enum Outcome {
        PUBLISHED,
        DUPLICATE,
        DATASET_MISSING,
        /** The notification could not be used (no codespace in the body) and was acked. */
        INVALID,
        FAILED
    }

    private final MeterRegistry registry;

    public NetexImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void count(Outcome outcome) {
        Counter.builder(NOTIFICATIONS)
                .description("NeTEx export notifications by outcome")
                .tag("outcome", outcome.name().toLowerCase())
                .register(registry)
                .increment();
    }
}
