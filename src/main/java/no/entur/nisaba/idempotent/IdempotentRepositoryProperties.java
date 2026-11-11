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

package no.entur.nisaba.idempotent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param gcs        the bucket of the markers (profile {@code gcs-blobstore})
 * @param staleAfter how long an unconfirmed claim is left alone before another claimer takes it over; longer
 *                   than any publish (a few blob lookups and one Kafka send bounded by the send timeout)
 * @param migration  settings of the one-shot migration from the Kafka idempotent topic
 */
@ConfigurationProperties(prefix = "nisaba.idempotent")
public record IdempotentRepositoryProperties(
        @DefaultValue Gcs gcs,
        @DefaultValue("15m") Duration staleAfter,
        @DefaultValue Migration migration) {

    public record Gcs(String bucket) {
    }

    /**
     * @param dryRun  list the keys and write nothing
     * @param threads concurrent creates
     */
    public record Migration(@DefaultValue("false") boolean dryRun, @DefaultValue("16") int threads) {
    }
}
