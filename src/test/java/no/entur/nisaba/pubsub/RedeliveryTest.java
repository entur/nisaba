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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedeliveryTest {

    /**
     * Records the waits instead of sleeping; {@code stoppingAfter} waits report the context as closing.
     */
    static final class RecordingInFlight extends InFlightMessages {
        final List<Duration> waits = new ArrayList<>();
        int stoppingAfter = Integer.MAX_VALUE;

        RecordingInFlight() {
            super(1);
        }

        @Override
        public boolean awaitOrStop(Duration duration) {
            waits.add(duration);
            return waits.size() > stoppingAfter;
        }
    }

    private final RecordingInFlight inFlight = new RecordingInFlight();
    private final Redelivery redelivery = new Redelivery(3, 5000, 3, inFlight);

    @Test
    void retriesWithTheCamelScheduleThenRethrows() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> redelivery.run(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals("boom", e.getMessage());
        assertEquals(4, attempts.get(), "the first attempt plus three redeliveries");
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45)), inFlight.waits);
    }

    @Test
    void succeedsOnTheSecondAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        redelivery.run(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("transient");
            }
        });
        assertEquals(2, attempts.get());
        assertEquals(List.of(Duration.ofSeconds(5)), inFlight.waits);
    }

    @Test
    void zeroRedeliveriesMeansNoRetry() {
        Redelivery none = new Redelivery(0, 5000, 3, inFlight);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> none.run(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, attempts.get());
        assertEquals(List.of(), inFlight.waits);
    }

    @Test
    void givesUpAtOnceWhenTheContextIsClosing() {
        inFlight.stoppingAfter = 1;
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> redelivery.run(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(2, attempts.get(), "one redelivery, then the second wait reports shutdown");
        assertEquals(2, inFlight.waits.size());
    }

    @Test
    void backOffIsCappedAtAMinuteLikeCamel() {
        Redelivery five = new Redelivery(5, 5000, 3, inFlight);
        assertThrows(IllegalStateException.class, () -> five.run(() -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(60), Duration.ofSeconds(60)), inFlight.waits);
    }

    @Test
    void errorsAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(OutOfMemoryError.class, () -> redelivery.run(() -> {
            attempts.incrementAndGet();
            throw new OutOfMemoryError("simulated");
        }));
        assertEquals(1, attempts.get());
    }
}
