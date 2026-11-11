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

import no.entur.nisaba.idempotent.ClaimInProgressException;
import no.entur.nisaba.pipeline.ImportMdc;
import no.entur.nisaba.pipeline.InvalidDatasetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Retries a failed notification in process before it is nacked, with the schedule of the Camel error handler:
 * {@code nisaba.redelivery.max} attempts, {@code nisaba.redelivery.delay.millis} before the first, multiplied by
 * {@code nisaba.redelivery.backoff.multiplier} each time, capped at a minute (3 attempts after 5 s, 15 s and 45 s by
 * default).
 * <p>
 * Unlike Camel, which redelivered the failing step, the whole message is re-run: every step is idempotent and
 * the download is the only cost. A wait is cut short when the context starts closing, so that a pod being
 * terminated nacks the message instead of sleeping into its grace period.
 * <p>
 * Each pod has one callback slot, so a local retry that cannot succeed holds every other notification back:
 * a dataset that cannot be read ({@link InvalidDatasetException}) is not retried locally, and a key claimed by
 * another pod ({@link ClaimInProgressException}) is retried once, which is enough for a claimer that is
 * publishing; if the claimer died the claim stays for minutes. Both are still nacked and redelivered by PubSub
 * with its own backoff.
 */
@Component
public class Redelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger(Redelivery.class);

    /**
     * Camel's {@code maximumRedeliveryDelay}: the back-off never grows beyond a minute.
     */
    static final long MAX_DELAY_MILLIS = 60_000;

    public interface Work {
        void run() throws Exception;
    }

    private final int maxRedeliveries;
    private final long initialDelayMillis;
    private final int backOffMultiplier;
    private final InFlightMessages inFlightMessages;

    public Redelivery(@Value("${nisaba.redelivery.max:3}") int maxRedeliveries,
                      @Value("${nisaba.redelivery.delay.millis:5000}") long initialDelayMillis,
                      @Value("${nisaba.redelivery.backoff.multiplier:3}") int backOffMultiplier,
                      InFlightMessages inFlightMessages) {
        this.maxRedeliveries = maxRedeliveries;
        this.initialDelayMillis = initialDelayMillis;
        this.backOffMultiplier = backOffMultiplier;
        this.inFlightMessages = inFlightMessages;
    }

    /**
     * @throws Exception the last failure, once the redeliveries are exhausted or the context is closing.
     */
    public void run(Work work) throws Exception {
        long delay = initialDelayMillis;
        for (int redelivery = 1; ; redelivery++) {
            try {
                work.run();
                return;
            } catch (Exception e) {
                if (redelivery > maxRedeliveriesFor(e)) {
                    throw e;
                }
                LOGGER.warn("{} Exchange failed, redelivering the message locally, attempt {}/{}...",
                        ImportMdc.prefix(), redelivery, maxRedeliveriesFor(e), e);
                if (inFlightMessages.awaitOrStop(Duration.ofMillis(delay))) {
                    LOGGER.warn("{}The application is shutting down, giving up local redelivery", ImportMdc.prefix());
                    throw e;
                }
                delay = Math.min(delay * backOffMultiplier, MAX_DELAY_MILLIS);
            }
        }
    }

    private int maxRedeliveriesFor(Exception e) {
        if (e instanceof InvalidDatasetException) {
            return 0;
        }
        if (e instanceof ClaimInProgressException) {
            return Math.min(1, maxRedeliveries);
        }
        return maxRedeliveries;
    }
}
