package no.entur.nisaba.routes;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.DefaultInterceptSendToEndpoint;
import org.apache.camel.support.EventNotifierSupport;
import org.entur.pubsub.base.EnturGooglePubSubAdmin;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Create PubSub topics and subscriptions on startup.
 * This is used only in unit tests and local environment.
 */
@Component
@Profile("google-pubsub-autocreate")
public class EventNotifier extends EventNotifierSupport {

    private final EnturGooglePubSubAdmin enturGooglePubSubAdmin;

    public EventNotifier(EnturGooglePubSubAdmin enturGooglePubSubAdmin) {
        this.enturGooglePubSubAdmin = enturGooglePubSubAdmin;
    }

    @Override
    public void notify(CamelEvent event) {

        if (event instanceof CamelEvent.CamelContextStartingEvent) {
            CamelContext context = ((CamelEvent.CamelContextStartingEvent) event).getContext();
            context.getEndpoints().stream().filter(e -> e.getEndpointUri().startsWith("google-pubsub")).forEach(this::createSubscriptionIfMissing);
        }

    }

    private void createSubscriptionIfMissing(Endpoint e) {
        GooglePubsubEndpoint gep;
        if (e instanceof GooglePubsubEndpoint) {
            gep = (GooglePubsubEndpoint) e;
        } else if (e instanceof DefaultInterceptSendToEndpoint) {
            gep = (GooglePubsubEndpoint) ((DefaultInterceptSendToEndpoint) e).getOriginalEndpoint();
        } else {
            throw new IllegalStateException("Incompatible endpoint: " + e);
        }
        enturGooglePubSubAdmin.createSubscriptionIfMissing(gep.getDestinationName());
    }

}