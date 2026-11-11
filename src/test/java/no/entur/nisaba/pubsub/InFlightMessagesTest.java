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

import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightMessagesTest {

    @Test
    void stopWaitsForTheMessageBeingProcessed() throws Exception {
        InFlightMessages inFlight = new InFlightMessages(5);
        inFlight.start();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<Void> processing = CompletableFuture.runAsync(() -> inFlight.track(() -> {
            started.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, inFlight.inFlightCount());

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(inFlight::stop);
        Thread.sleep(300);
        assertFalse(stopping.isDone(), "stop waits while a message is in flight");
        finish.countDown();
        stopping.get(5, TimeUnit.SECONDS);
        processing.get(5, TimeUnit.SECONDS);
        assertEquals(0, inFlight.inFlightCount());
        assertFalse(inFlight.isRunning());
    }

    @Test
    void stopGivesUpAfterTheDrainTimeout() throws Exception {
        InFlightMessages inFlight = new InFlightMessages(1);
        inFlight.start();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> inFlight.track(() -> {
            started.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long startedAt = System.nanoTime();
        inFlight.stop();
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofMillis(900)) > 0);
        assertEquals(1, inFlight.inFlightCount());
        finish.countDown();
    }

    @Test
    void contextClosedEventCutsAWaitShort() throws Exception {
        InFlightMessages inFlight = new InFlightMessages(5);
        inFlight.start();
        assertFalse(inFlight.isStopping());
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> inFlight.awaitOrStop(Duration.ofSeconds(30)));
        Thread.sleep(100);
        assertFalse(waiting.isDone());
        inFlight.onContextClosed(new ContextClosedEvent(new StaticApplicationContext()));
        assertTrue(waiting.get(5, TimeUnit.SECONDS), "the wait reports that the context is closing");
        assertTrue(inFlight.isStopping());
    }

    @Test
    void awaitReturnsFalseAfterTheFullDurationWhenNothingHappens() {
        InFlightMessages inFlight = new InFlightMessages(5);
        assertFalse(inFlight.awaitOrStop(Duration.ofMillis(50)));
    }

    @Test
    void stopsFirst() {
        assertEquals(Integer.MAX_VALUE, new InFlightMessages(5).getPhase());
    }
}
