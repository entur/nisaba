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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets a notification being processed finish before the application context closes.
 * <p>
 * The PubSub subscriber is stopped by a {@code ContextClosedEvent} listener in the consumer base class, so
 * nothing new arrives; {@link #stop()} runs after those listeners and before beans are destroyed, so the blob
 * store and Kafka are still usable by the work draining. The highest lifecycle phase makes it the first
 * {@code SmartLifecycle} to stop. {@code spring.lifecycle.timeout-per-shutdown-phase} does not bound this
 * wait, because {@code stop()} is invoked inline on the closing thread.
 */
@Component
public class InFlightMessages implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(InFlightMessages.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final AtomicInteger inFlight = new AtomicInteger();
    private final CountDownLatch stopping = new CountDownLatch(1);
    private final Duration drainTimeout;
    private volatile boolean running;

    public InFlightMessages(@Value("${nisaba.shutdown.drain.timeout.seconds:45}") long drainTimeoutSeconds) {
        this.drainTimeout = Duration.ofSeconds(drainTimeoutSeconds);
    }

    /**
     * Runs {@code processing}, counting it as in flight for as long as it takes. Failures propagate, so the
     * consumer base class still nacks the message.
     */
    public void track(Runnable processing) {
        inFlight.incrementAndGet();
        try {
            processing.run();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public int inFlightCount() {
        return inFlight.get();
    }

    public boolean isStopping() {
        return stopping.getCount() == 0;
    }

    /**
     * Waits for the duration, or less if the context starts closing meanwhile.
     *
     * @return true when the context is closing and the caller should give up rather than keep retrying.
     */
    public boolean awaitOrStop(Duration duration) {
        try {
            return stopping.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * Fires before the lifecycle stops (and at the latest together with the subscriber close, which waits up
     * to 10 s for the running callback), so a retry sleeping in that callback wakes up and nacks instead.
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onContextClosed(ContextClosedEvent event) {
        stopping.countDown();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        running = false;
        stopping.countDown();
        if (inFlight.get() == 0) {
            return;
        }
        LOGGER.info("Waiting up to {} s for {} message(s) still being processed", drainTimeout.toSeconds(), inFlight.get());
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (inFlight.get() > 0 && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while draining. {} message(s) will be redelivered.", inFlight.get());
                return;
            }
        }
        int abandoned = inFlight.get();
        if (abandoned > 0) {
            LOGGER.warn("Gave up draining after {} s. {} message(s) will be redelivered.", drainTimeout.toSeconds(), abandoned);
        } else {
            LOGGER.info("Finished the in-flight messages, shutting down");
        }
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
