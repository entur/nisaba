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

import no.entur.nisaba.TestApp;
import no.entur.nisaba.kafka.KafkaTestDoubles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drain must be the first lifecycle to stop, and its wait must not be capped by Spring's per-phase timeout:
 * if it ever were, the drain would silently cap at that timeout. The context is built and closed by the test
 * itself, since closing it is the point.
 */
class ShutdownOrderTest {

    @Test
    void drainStopsFirstAndIsNotBoundedByThePhaseTimeout() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class, KafkaTestDoubles.class)
                .web(WebApplicationType.NONE)
                .profiles("test", "in-memory-blobstore")
                .properties("spring.lifecycle.timeout-per-shutdown-phase=1ms", "nisaba.shutdown.drain.timeout.seconds=10")
                .run();
        InFlightMessages inFlightMessages = context.getBean(InFlightMessages.class);
        try {
            Map<String, SmartLifecycle> lifecycles = context.getBeansOfType(SmartLifecycle.class);
            for (Map.Entry<String, SmartLifecycle> entry : lifecycles.entrySet()) {
                if (entry.getValue() != inFlightMessages) {
                    assertTrue(entry.getValue().getPhase() < inFlightMessages.getPhase(),
                            entry.getKey() + " has phase " + entry.getValue().getPhase());
                }
            }

            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<Void> processing = CompletableFuture.runAsync(() -> inFlightMessages.track(() -> {
                started.countDown();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long startedAt = System.nanoTime();
            context.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - startedAt);
            assertTrue(closing.compareTo(Duration.ofMillis(1400)) > 0, "the context waited for the message: " + closing);
            assertTrue(inFlightMessages.isStopping());
            processing.get(5, TimeUnit.SECONDS);
            assertEquals(0, inFlightMessages.inFlightCount());
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }
}
