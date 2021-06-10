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
import no.entur.nisaba.event.DatasetStat;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.rutebanken.netex.validation.NeTExValidator;
import org.springframework.boot.test.context.SpringBootTest;
import org.xml.sax.SAXException;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.zip.ZipInputStream;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.CURRENT_AGGREGATED_NETEX_FILENAME;
import static no.entur.nisaba.Constants.DATASET_STAT;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestApp.class)
class NetexPublicationRouteBuilderTest extends NisabaRouteBuilderIntegrationTestBase {

    private static final String CODESPACE_AVI = "avi";
    private static final String CODESPACE_NOR = "nor";
    private static final String CODESPACE_RUT = "rut";
    private static final String CODESPACE_ATB = "atb";

    @Produce("google-pubsub:{{nisaba.pubsub.project.id}}:NetexExportNotificationQueue")
    protected ProducerTemplate exportNotificationQueueProducerTemplate;

    @EndpointInject("mock:retrieveDatasetCreationTime")
    protected MockEndpoint mockRetrieveDatasetCreationTime;

    @EndpointInject("mock:processCommonFile")
    protected MockEndpoint mockProcessCommonFile;

    @EndpointInject("mock:publishDataset")
    protected MockEndpoint mockPublishDataset;

    @EndpointInject("mock:processLineFile")
    protected MockEndpoint mockProcessLineFile;

    @EndpointInject("mock:nisabaCommonTopic")
    protected MockEndpoint mockNisabaCommonTopic;

    @EndpointInject("mock:nisabaServiceJourneyTopic")
    protected MockEndpoint mockNisabaServiceJourneyTopic;


    @Test
    void testPublishServiceJourney() throws Exception {

        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));
        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));

        mockRetrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_CREATION_TIME, LocalDateTime.now()));
        mockProcessCommonFile.expectedMessageCount(1);
        mockProcessCommonFile.whenAnyExchangeReceived(e -> e.getIn().setHeader(DATASET_STAT, new DatasetStat()));
        mockNisabaServiceJourneyTopic.expectedMessageCount(24);
        mockNisabaServiceJourneyTopic.setResultWaitTime(30000);

        mardukInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE_AVI + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip"));

        context.start();
        exportNotificationQueueProducerTemplate.sendBody(CODESPACE_AVI);
        mockNisabaServiceJourneyTopic.assertIsSatisfied();

        mockNisabaServiceJourneyTopic.getReceivedExchanges().forEach(this::validatePublicationDelivery);

    }


    @Test
    void testPublishServiceJourneyWithNotices() throws Exception {

        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));
        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));

        mockRetrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_CREATION_TIME, LocalDateTime.now()));
        mockProcessCommonFile.expectedMessageCount(1);
        mockProcessCommonFile.whenAnyExchangeReceived(e -> e.getIn().setHeader(DATASET_STAT, new DatasetStat()));
        mockNisabaServiceJourneyTopic.expectedMessageCount(24);
        mockNisabaServiceJourneyTopic.setResultWaitTime(30000);

        mardukInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE_RUT + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_rut-aggregated-netex.zip"));

        context.start();
        exportNotificationQueueProducerTemplate.sendBody(CODESPACE_RUT);
        mockNisabaServiceJourneyTopic.assertIsSatisfied();

        mockNisabaServiceJourneyTopic.getReceivedExchanges().forEach(this::validatePublicationDelivery);

    }

    @Test
    void testPublishServiceJourneyWithInterchanges() throws Exception {

        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));
        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));


        mockRetrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_CREATION_TIME, LocalDateTime.now()));
        mockProcessCommonFile.expectedMessageCount(1);
        mockProcessCommonFile.whenAnyExchangeReceived(e -> e.getIn().setHeader(DATASET_STAT, new DatasetStat()));
        mockNisabaServiceJourneyTopic.expectedMessageCount(24);
        mockNisabaServiceJourneyTopic.setResultWaitTime(30000);

        mardukInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE_ATB + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_atb-aggregated-netex.zip"));

        context.start();
        exportNotificationQueueProducerTemplate.sendBody(CODESPACE_ATB);
        mockNisabaServiceJourneyTopic.assertIsSatisfied();

        mockNisabaServiceJourneyTopic.getReceivedExchanges().forEach(this::validatePublicationDelivery);

    }

    @Test
    void testPublishCommonFile() throws Exception {

        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
        AdviceWith.adviceWith(context, "publish-common-file", a -> a.weaveById("to-kafka-topic-common").replace().to("mock:nisabaCommonTopic"));
        AdviceWith.adviceWith(context, "process-service-journey", a -> a.weaveById("to-kafka-topic-servicejourney").replace().to("mock:nisabaServiceJourneyTopic"));

        // expect filtered common file + scheduled stop points + stop assignments + route points + service links
        mockNisabaCommonTopic.expectedMessageCount(10);
        mockNisabaCommonTopic.setResultWaitTime(100000);
        mockProcessLineFile.expectedMessageCount(0);

        mardukInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE_NOR + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_nor-aggregated-netex.zip"));

        context.start();
        exportNotificationQueueProducerTemplate.sendBody(CODESPACE_NOR);

        mockNisabaCommonTopic.assertIsSatisfied();
        mockProcessLineFile.assertIsSatisfied();

        mockNisabaCommonTopic.getReceivedExchanges().forEach(this::validatePublicationDelivery);

    }

    private void validatePublicationDelivery(Exchange exchange) {
        byte[] body = exchange.getIn().getBody(byte[].class);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(body))) {
            zis.getNextEntry();
            String netex = new String(zis.readAllBytes());
            NeTExValidator neTExValidator = NeTExValidator.getNeTExValidator();
            neTExValidator.validate(new StreamSource(new StringReader(netex)));
        } catch (IOException | SAXException e) {
            Assertions.fail(e);
        }
    }


}
