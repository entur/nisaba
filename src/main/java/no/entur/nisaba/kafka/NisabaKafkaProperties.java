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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * nisaba's own Kafka settings. The client settings (brokers, SASL, schema registry) come from Spring Boot's
 * {@code spring.kafka.*}.
 *
 * @param topic         the event topic, and the idempotent topic of the previous versions (read by the migration only).
 * @param sendTimeout   how long a synchronous produce may take.
 * @param publishVerificationTimeout how long to wait for an event whose send failed to be read back from the
 *                      topic before the failure is reported and the dataset published again.
 * @param replayTimeout how long the start-up replay of a topic may take; must stay well inside the start-up probe.
 * @param pollTimeout   the consumer poll duration.
 */
@ConfigurationProperties(prefix = "nisaba.kafka")
public record NisabaKafkaProperties(
        Topic topic,
        @DefaultValue("30s") Duration sendTimeout,
        @DefaultValue("5s") Duration publishVerificationTimeout,
        @DefaultValue("2m") Duration replayTimeout,
        @DefaultValue("1s") Duration pollTimeout) {

    public record Topic(String event, String idempotent) {
    }
}
