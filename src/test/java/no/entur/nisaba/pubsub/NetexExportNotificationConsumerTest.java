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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import no.entur.nisaba.pipeline.ImportMdc;
import no.entur.nisaba.pipeline.NetexImportMetrics;
import no.entur.nisaba.pipeline.NetexImportPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The consumer's own contract: identity in the MDC before the pipeline runs, the failure metric once per
 * message, the exception propagated for the base class to nack, and the fully-qualified subscription name.
 */
class NetexExportNotificationConsumerTest {

    private final NetexImportPipeline pipeline = mock(NetexImportPipeline.class);
    private final InFlightMessages inFlightMessages = new InFlightMessages(1);
    private final Redelivery redelivery = new Redelivery(1, 1, 1, inFlightMessages);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NetexExportNotificationConsumer consumer = new NetexExportNotificationConsumer(
            pipeline, inFlightMessages, redelivery, new NetexImportMetrics(registry), "ent-marduk-prd");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void subscribesWithTheFullyQualifiedNameInMardukProject() {
        assertEquals("projects/ent-marduk-prd/subscriptions/NetexExportNotificationQueue", consumer.getDestinationName());
    }

    @Test
    void identityIsInTheMdcWhileThePipelineRuns() {
        List<String> prefixes = new ArrayList<>();
        doAnswer(invocation -> {
            prefixes.add(ImportMdc.prefix());
            return null;
        }).when(pipeline).process(any());

        consumer.onMessage("avi".getBytes(StandardCharsets.UTF_8), Map.of("RutebankenCorrelationId", "corr-1"));

        assertEquals(List.of("[codespace=avi correlationId=corr-1] "), prefixes);
        assertEquals("avi", MDC.get("codespace"), "left in place for the base class's own log line");
        assertEquals(0, inFlightMessages.inFlightCount());
    }

    @Test
    void failureIsCountedOncePerMessageAndPropagates() {
        doThrow(new IllegalStateException("boom")).when(pipeline).process(any());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> consumer.onMessage("avi".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertEquals("boom", e.getMessage());
        assertEquals(1.0, registry.get(NetexImportMetrics.NOTIFICATIONS).tag("outcome", "failed").counter().count());
        assertEquals(0, inFlightMessages.inFlightCount());
    }

    @Test
    void blankBodyIsCountedAndAckedWithoutRunningAnything() {
        consumer.onMessage(" ".getBytes(StandardCharsets.UTF_8), Map.of());

        verifyNoInteractions(pipeline);
        assertEquals(1.0, registry.get(NetexImportMetrics.NOTIFICATIONS).tag("outcome", "invalid").counter().count());
        assertEquals(0, inFlightMessages.inFlightCount());
    }

    @Test
    void errorIsCountedAndRethrownAsAnExceptionTheBaseClassNacks() {
        doThrow(new StackOverflowError()).when(pipeline).process(any());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> consumer.onMessage("avi".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertInstanceOf(StackOverflowError.class, e.getCause());
        assertEquals(1.0, registry.get(NetexImportMetrics.NOTIFICATIONS).tag("outcome", "failed").counter().count());
        assertEquals(0, inFlightMessages.inFlightCount());
    }
}
