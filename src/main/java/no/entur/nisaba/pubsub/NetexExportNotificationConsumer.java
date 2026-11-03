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

import com.google.pubsub.v1.ProjectSubscriptionName;
import no.entur.nisaba.pipeline.ImportMdc;
import no.entur.nisaba.pipeline.NetexImportMetrics;
import no.entur.nisaba.pipeline.NetexImportPipeline;
import org.entur.pubsub.base.AbstractEnturGooglePubSubConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Receives Marduk's NeTEx export notifications and runs the import pipeline on them, one message at a time.
 * <p>
 * Replaces the Camel {@code master:...:google-pubsub:...} route. Every pod consumes the subscription: the
 * idempotent claim is atomic across pods, so no leader is needed. The base class acks the message when this
 * method returns and nacks it when it throws; PubSub then redelivers it, to any pod, after its retry backoff.
 * The subscription lives in Marduk's project, hence the fully-qualified name.
 */
@Component
@ConditionalOnProperty(value = "nisaba.pubsub.consumer.enabled", matchIfMissing = true)
public class NetexExportNotificationConsumer extends AbstractEnturGooglePubSubConsumer {

    private final NetexImportPipeline pipeline;
    private final InFlightMessages inFlightMessages;
    private final Redelivery redelivery;
    private final NetexImportMetrics metrics;
    private final String mardukProjectId;

    public NetexExportNotificationConsumer(NetexImportPipeline pipeline, InFlightMessages inFlightMessages,
                                           Redelivery redelivery, NetexImportMetrics metrics,
                                           @Value("${marduk.pubsub.project.id}") String mardukProjectId) {
        this.pipeline = pipeline;
        this.inFlightMessages = inFlightMessages;
        this.redelivery = redelivery;
        this.metrics = metrics;
        this.mardukProjectId = mardukProjectId;
    }

    @Override
    protected String getDestinationName() {
        return ProjectSubscriptionName.format(mardukProjectId, NisabaQueues.NETEX_EXPORT_NOTIFICATION_QUEUE);
    }

    @Override
    public void onMessage(byte[] content, Map<String, String> attributes) {
        ImportMdc.clear();
        NetexExportNotification notification = NetexExportNotification.from(content, attributes);
        ImportMdc.put(notification.codespace(), notification.correlationId());
        inFlightMessages.track(() -> {
            try {
                redelivery.run(() -> pipeline.process(notification));
            } catch (Exception e) {
                metrics.count(NetexImportMetrics.Outcome.FAILED);
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * The base class closes the subscriber here and waits up to 10 s for the running callback. Trip the drain's
     * latch first, so that a local retry sleeping in that callback wakes up and nacks at once, whatever order
     * Spring gives the two listeners.
     */
    @Override
    public void handleContextClosedEvent(ContextClosedEvent contextClosedEvent) {
        inFlightMessages.onContextClosed(contextClosedEvent);
        super.handleContextClosedEvent(contextClosedEvent);
    }
}
