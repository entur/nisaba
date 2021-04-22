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

import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage;
import no.entur.nisaba.Constants;
import org.apache.camel.Exchange;
import org.apache.camel.ExtendedExchange;
import org.apache.camel.Message;
import org.apache.camel.ServiceStatus;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.hazelcast.policy.HazelcastRoutePolicy;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.Synchronization;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.entur.pubsub.camel.EnturGooglePubSubConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static no.entur.nisaba.Constants.SINGLETON_ROUTE_DEFINITION_GROUP_NAME;


/**
 * Defines common route behavior.
 */
public abstract class BaseRouteBuilder extends RouteBuilder {

    @Value("${quartz.lenient.fire.time.ms:180000}")
    private int lenientFireTimeMs;

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

    }

    protected void logRedelivery(Exchange exchange) {
        int redeliveryCounter = exchange.getIn().getHeader("CamelRedeliveryCounter", Integer.class);
        int redeliveryMaxCounter = exchange.getIn().getHeader("CamelRedeliveryMaxCounter", Integer.class);
        Throwable camelCaughtThrowable = exchange.getProperty("CamelExceptionCaught", Throwable.class);
        Throwable rootCause = ExceptionUtils.getRootCause(camelCaughtThrowable);

        String rootCauseType = rootCause != null ? rootCause.getClass().getName() : "";
        String rootCauseMessage = rootCause != null ? rootCause.getMessage() : "";

        log.warn("Exchange failed ({}: {}) . Redelivering the message locally, attempt {}/{}...", rootCauseType, rootCauseMessage, redeliveryCounter, redeliveryMaxCounter);
    }

    protected String logDebugShowAll() {
        return "log:" + getClass().getName() + "?level=DEBUG&showAll=true&multiline=true";
    }

    /**
     * Add ACK/NACK completion callback for an aggregated exchange.
     * The callback should be added after the aggregation is complete to prevent individual messages from being acked
     * by the aggregator.
     */
    protected void addOnCompletionForAggregatedExchange(Exchange exchange) {

        List<Message> messages = exchange.getIn().getBody(List.class);
        List<BasicAcknowledgeablePubsubMessage> ackList = messages.stream()
                .map(m -> m.getHeader(EnturGooglePubSubConstants.ACK_ID, BasicAcknowledgeablePubsubMessage.class))
                .collect(Collectors.toList());

        exchange.adapt(ExtendedExchange.class).addOnCompletion(new AckSynchronization(ackList));
    }


    private static class AckSynchronization implements Synchronization {

        private final List<BasicAcknowledgeablePubsubMessage> ackList;

        public AckSynchronization(List<BasicAcknowledgeablePubsubMessage> ackList) {
            this.ackList = ackList;
        }

        @Override
        public void onComplete(Exchange exchange) {
            ackList.forEach(BasicAcknowledgeablePubsubMessage::ack);
        }

        @Override
        public void onFailure(Exchange exchange) {
            ackList.forEach(BasicAcknowledgeablePubsubMessage::nack);
        }
    }


    protected void setNewCorrelationId(Exchange e) {
        e.getIn().setHeader(Constants.CORRELATION_ID, UUID.randomUUID().toString());
    }

    protected void setCorrelationIdIfMissing(Exchange e) {
        e.getIn().setHeader(Constants.CORRELATION_ID, e.getIn().getHeader(Constants.CORRELATION_ID, UUID.randomUUID().toString()));
    }

    protected String correlation() {
        return "[codespace=${header." + Constants.DATASET_CODESPACE + "} correlationId=${header." + Constants.CORRELATION_ID + "}] ";
    }

    protected void removeAllCamelHeaders(Exchange e) {
        e.getIn().removeHeaders(Constants.CAMEL_ALL_HEADERS, EnturGooglePubSubConstants.ACK_ID);
    }

    protected void removeAllCamelHttpHeaders(Exchange e) {
        e.getIn().removeHeaders(Constants.CAMEL_ALL_HTTP_HEADERS, EnturGooglePubSubConstants.ACK_ID);
    }

    /**
     * Create a new singleton route definition from URI. Only one such route should be active throughout the cluster at any time.
     */
    protected RouteDefinition singletonFrom(String uri) {
        return this.from(uri).group(SINGLETON_ROUTE_DEFINITION_GROUP_NAME);
    }

    protected boolean isStarted(String routeId) {
        ServiceStatus status = getContext().getRouteController().getRouteStatus(routeId);
        return status != null && status.isStarted();
    }

    protected boolean isLeader(String routeId) {
        List<RoutePolicy> routePolicyList = getContext().getRoute(routeId).getRoutePolicyList();
        if (routePolicyList != null) {
            for (RoutePolicy routePolicy : routePolicyList) {
                if (routePolicy instanceof HazelcastRoutePolicy) {
                    return ((HazelcastRoutePolicy) (routePolicy)).isLeader();
                }
            }
        }
        return false;
    }

    protected void deleteDirectoryRecursively(String directory) {

        log.debug("Deleting local directory {} ...", directory);
        try {
            Path pathToDelete = Paths.get(directory);
            boolean deleted = FileSystemUtils.deleteRecursively(pathToDelete);
            if (deleted) {
                log.debug("Local directory {} cleanup done.", directory);
            } else {
                log.debug("The directory {} did not exist, ignoring deletion request", directory);
            }
        } catch (IOException e) {
            log.warn("Failed to delete directory {}", directory, e);
        }
    }

}
