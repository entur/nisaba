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

package no.entur.nisaba.routes;

import no.entur.nisaba.Constants;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.google.pubsub.GooglePubsubConstants;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Defines common route behavior.
 */
public abstract class BaseRouteBuilder extends RouteBuilder {

    @Value("${nisaba.camel.redelivery.max:3}")
    private int maxRedelivery;

    @Value("${nisaba.camel.redelivery.delay:5000}")
    private int redeliveryDelay;

    @Value("${nisaba.camel.redelivery.backoff.multiplier:3}")
    private int backOffMultiplier;

    @Override
    public void configure() throws Exception {
        errorHandler(defaultErrorHandler()
                .redeliveryDelay(redeliveryDelay)
                .maximumRedeliveries(maxRedelivery)
                .onRedelivery(this::logRedelivery)
                .useExponentialBackOff()
                .backOffMultiplier(backOffMultiplier)
                .logExhausted(true)
                .logRetryStackTrace(true));


        // Copy all PubSub headers except the internal Camel PubSub headers from the PubSub message into the Camel message headers.
        interceptFrom("google-pubsub:*")
                .process(exchange ->
                {
                    Map<String, String> pubSubAttributes = exchange.getIn().getHeader(GooglePubsubConstants.ATTRIBUTES, Map.class);
                    pubSubAttributes.entrySet().stream().filter(entry -> !entry.getKey().startsWith("CamelGooglePubsub")).forEach(entry -> exchange.getIn().setHeader(entry.getKey(), entry.getValue()));
                });

        // Copy only the import key, correlationId and codespace headers from the Camel message into the PubSub message by default.
        interceptSendToEndpoint("google-pubsub:*").process(
                exchange -> {
                    Map<String, String> pubSubAttributes = new HashMap<>(exchange.getIn().getHeader(GooglePubsubConstants.ATTRIBUTES, new HashMap<>(), Map.class));
                    if (exchange.getIn().getHeader(Constants.DATASET_IMPORT_KEY) != null) {
                        pubSubAttributes.put(Constants.DATASET_IMPORT_KEY, exchange.getIn().getHeader(Constants.DATASET_IMPORT_KEY, String.class));
                    }
                    if (exchange.getIn().getHeader(Constants.CORRELATION_ID) != null) {
                        pubSubAttributes.put(Constants.CORRELATION_ID, exchange.getIn().getHeader(Constants.CORRELATION_ID, String.class));
                    }
                    if (exchange.getIn().getHeader(Constants.DATASET_CODESPACE) != null) {
                        pubSubAttributes.put(Constants.DATASET_CODESPACE, exchange.getIn().getHeader(Constants.DATASET_CODESPACE, String.class));
                    }
                    exchange.getIn().setHeader(GooglePubsubConstants.ATTRIBUTES, pubSubAttributes);

                });

    }

    protected void logRedelivery(Exchange exchange) {
        int redeliveryCounter = exchange.getIn().getHeader("CamelRedeliveryCounter", Integer.class);
        int redeliveryMaxCounter = exchange.getIn().getHeader("CamelRedeliveryMaxCounter", Integer.class);
        Throwable camelCaughtThrowable = exchange.getProperty("CamelExceptionCaught", Throwable.class);
        String correlation = simple(correlation(), String.class).evaluate(exchange, String.class);

        log.warn("{} Exchange failed, redelivering the message locally, attempt {}/{}...", correlation, redeliveryCounter, redeliveryMaxCounter, camelCaughtThrowable);
    }

    protected String logDebugShowAll() {
        return "log:" + getClass().getName() + "?level=DEBUG&showAll=true&multiline=true&showCachedStreams=false";
    }

    protected void setCorrelationIdIfMissing(Exchange e) {
        e.getIn().setHeader(Constants.CORRELATION_ID, e.getIn().getHeader(Constants.CORRELATION_ID, UUID.randomUUID().toString()));
    }

    protected String correlation() {
        return "[codespace=${header." + Constants.DATASET_CODESPACE + "} correlationId=${header." + Constants.CORRELATION_ID + "}] ";
    }
}
