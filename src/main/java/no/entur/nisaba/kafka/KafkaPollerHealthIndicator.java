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

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DOWN when the poller of the event topic has died: the import dates served by the REST API then go stale. Visible on
 * {@code /actuator/health} as {@code kafkaPoller}; not in the liveness group until its behaviour has been
 * observed in production.
 */
@Component("kafkaPoller")
@Profile("!test")
public class KafkaPollerHealthIndicator implements HealthIndicator {

    private final ImportDateMapReader importDateMapReader;

    public KafkaPollerHealthIndicator(ImportDateMapReader importDateMapReader) {
        this.importDateMapReader = importDateMapReader;
    }

    @Override
    public Health health() {
        boolean reader = importDateMapReader.isAlive();
        Health.Builder health = reader ? Health.up() : Health.down();
        return health
                .withDetail("importDateMap", reader ? "following" : "stopped")
                .withDetail("pollers", List.of("import-date-map-poller"))
                .build();
    }
}
