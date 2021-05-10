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

package no.entur.nisaba.routes.netex.notification;

import no.entur.nisaba.Constants;
import no.entur.nisaba.NisabaRouteBuilderIntegrationTestBase;
import no.entur.nisaba.TestApp;
import no.entur.nisaba.avro.NetexImportEvent;
import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.CURRENT_AGGREGATED_NETEX_FILENAME;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestApp.class)
class NetexImportEventNotificationQueueRouteBuilderTest extends NisabaRouteBuilderIntegrationTestBase {

    private static final String CODESPACE = "avi";

    @Produce("google-pubsub:{{nisaba.pubsub.project.id}}:NetexExportNotificationQueue")
    protected ProducerTemplate producerTemplate;

    @Produce("direct:parseCreatedAttribute")
    protected ProducerTemplate parseCreatedAttribute;

    @EndpointInject("mock:retrieveDatasetCreationTime")
    protected MockEndpoint retrieveDatasetCreationTime;

    @EndpointInject("mock:nisabaEventTopic")
    protected MockEndpoint nisabaEventTopic;

    @EndpointInject("mock:nisabaCommonTopic")
    protected MockEndpoint nisabaCommonTopic;

    @EndpointInject("mock:nisabaServiceJourneyTopic")
    protected MockEndpoint nisabaServiceJourneyTopic;


    @EndpointInject("mock:checkCreatedAttribute")
    protected MockEndpoint checkCreatedAttribute;

    @Test
    void testNotification() throws Exception {

        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));
        AdviceWith.adviceWith(context, "notify-consumers-if-new", a -> a.weaveByToUri("direct:publishServiceJourneys").replace().to("mock:sink"));
        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));

        LocalDateTime now = LocalDateTime.now();
        retrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_CREATION_TIME, now));
        nisabaEventTopic.expectedMessageCount(1);

        nisabaInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip"),
                false);

        context.start();
        producerTemplate.sendBody(CODESPACE);
        nisabaEventTopic.assertIsSatisfied(20000);
        NetexImportEvent netexImportEvent = nisabaEventTopic.getReceivedExchanges().get(0).getIn().getBody(NetexImportEvent.class);
        Assertions.assertEquals("avi", netexImportEvent.getCodespace().toString());
        Assertions.assertEquals(now.truncatedTo(ChronoUnit.SECONDS), LocalDateTime.parse(netexImportEvent.getImportDateTime().toString()));
    }

    @Test
    void testParseCreatedAttribute() throws Exception {

        AdviceWith.adviceWith(context, "parse-created-attribute", a -> a.weaveAddLast().to("mock:checkCreatedAttribute"));
        AdviceWith.adviceWith(context, "notify-consumers-if-new", a -> a.weaveByToUri("direct:publishServiceJourneys").replace().to("mock:sink"));
        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));

        checkCreatedAttribute.expectedBodiesReceived(LocalDateTime.parse("2021-04-13T09:09:45.409"));

        context.start();
        parseCreatedAttribute.sendBody(IOUtils.toString(getClass().getResourceAsStream("/no/entur/nisaba/netex/import/_AVI_shared_data.xml"), StandardCharsets.UTF_8));
        checkCreatedAttribute.assertIsSatisfied(20000);
    }

    @Test
    void testExtractServiceJourney() throws Exception {

        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));
        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));

        retrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_CREATION_TIME, LocalDateTime.now()));
        nisabaCommonTopic.expectedMessageCount(1);
        nisabaServiceJourneyTopic.expectedMessageCount(24);

        nisabaInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip"),
                false);

        context.start();
        producerTemplate.sendBody(CODESPACE);
        nisabaCommonTopic.assertIsSatisfied(20000);
        nisabaServiceJourneyTopic.assertIsSatisfied(20000);
    }

    @Test
    @Disabled
    void testCommonFile() throws Exception {

        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));
        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));

        retrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_CREATION_TIME, LocalDateTime.now()));
        nisabaCommonTopic.expectedMessageCount(6);

        nisabaInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_nor-aggregated-netex.zip"),
                false);

        context.start();
        producerTemplate.sendBody(CODESPACE);
        nisabaCommonTopic.assertIsSatisfied(20000);

    }


}
