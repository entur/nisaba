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
import no.entur.nisaba.event.NetexImportEventKeyFactory;
import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.CURRENT_AGGREGATED_NETEX_FILENAME;
import static no.entur.nisaba.Constants.DATASET_ALL_CREATION_TIMES;
import static no.entur.nisaba.Constants.DATASET_CHOUETTE_IMPORT_KEY;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestApp.class)
class NetexImportEventNotificationQueueRouteBuilderTest extends NisabaRouteBuilderIntegrationTestBase {

    private static final String CODESPACE_AVI = "avi";

    @Produce("google-pubsub:{{marduk.pubsub.project.id}}:NetexExportNotificationQueue")
    protected ProducerTemplate exportNotificationQueueProducerTemplate;

    @Produce("direct:parseCreatedAttribute")
    protected ProducerTemplate parseCreatedAttribute;

    @Produce("direct:findChouetteImportKey")
    protected ProducerTemplate findChouetteImportKey;

    @EndpointInject("mock:retrieveDatasetCreationTime")
    protected MockEndpoint mockRetrieveDatasetCreationTime;

    @EndpointInject("mock:publishDataset")
    protected MockEndpoint mockPublishDataset;

    @EndpointInject("mock:nisabaEventTopic")
    protected MockEndpoint mockNisabaEventTopic;

    @EndpointInject("mock:checkCreatedAttribute")
    protected MockEndpoint mockCheckCreatedAttribute;

    @EndpointInject("mock:checkFindChouetteImportKey")
    protected MockEndpoint mockCheckFindChouetteImportKey;


    @Test
    void testNotification() throws Exception {

        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:retrieveDatasetCreationTime").replace().to("mock:retrieveDatasetCreationTime"));
        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));

        LocalDateTime now = LocalDateTime.now();
        mockRetrieveDatasetCreationTime.whenAnyExchangeReceived(exchange -> exchange.getIn().setHeader(Constants.DATASET_LATEST_CREATION_TIME, now));
        mockNisabaEventTopic.expectedMessageCount(1);
        mockNisabaEventTopic.setResultWaitTime(30000);

        mardukInMemoryBlobStoreRepository.uploadBlob(BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + CODESPACE_AVI + "-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip"));

        context.start();
        exportNotificationQueueProducerTemplate.sendBody(CODESPACE_AVI);
        mockNisabaEventTopic.assertIsSatisfied();
        NetexImportEvent netexImportEvent = mockNisabaEventTopic.getReceivedExchanges().getFirst().getIn().getBody(NetexImportEvent.class);
        Assertions.assertEquals("avi", netexImportEvent.getCodespace().toString());
        Assertions.assertEquals(now.truncatedTo(ChronoUnit.MILLIS), LocalDateTime.parse(netexImportEvent.getImportDateTime().toString()));
    }

    @Test
    void testParseCreatedAttribute() throws Exception {

        AdviceWith.adviceWith(context, "parse-created-attribute", a -> a.weaveAddLast().to("mock:checkCreatedAttribute"));
        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));

        mockCheckCreatedAttribute.expectedBodiesReceived(LocalDateTime.parse("2021-04-13T09:09:45.409"));

        context.start();
        parseCreatedAttribute.sendBody(IOUtils.toString(getClass().getResourceAsStream("/no/entur/nisaba/netex/import/_AVI_shared_data.xml"), StandardCharsets.UTF_8));
        mockCheckCreatedAttribute.assertIsSatisfied();
    }

    @Test
    void testFindChouetteImportKey() throws Exception {

        AdviceWith.adviceWith(context, "find-chouette-import-key", a -> a.weaveAddLast().to("mock:checkFindChouetteImportKey"));
        AdviceWith.adviceWith(context, "notify-consumers", a -> a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));

        List<LocalDateTime> localDateTimes = List.of(
                LocalDateTime.of(2021, 6, 4, 10, 10, 0, 0),
                LocalDateTime.of(2021, 6, 5, 10, 10, 0, 0),
                LocalDateTime.of(2021, 6, 6, 10, 10, 0, 0)
        );
        String importKey = NetexImportEventKeyFactory.createNetexImportEventKey("avi", localDateTimes.get(1));
        nisabaExchangeInMemoryBlobStoreRepository.uploadBlob("imported/avi/" + importKey + ".zip", new ByteArrayInputStream("test".getBytes()));
        mockCheckFindChouetteImportKey.expectedMessageCount(1);


        context.start();
        findChouetteImportKey.sendBodyAndHeaders("",
                Map.of(DATASET_CODESPACE, "avi",
                        DATASET_ALL_CREATION_TIMES, localDateTimes)
        );
        mockCheckFindChouetteImportKey.assertIsSatisfied();
        String originalImportKey = mockCheckFindChouetteImportKey.getReceivedExchanges().getFirst().getIn().getHeader(DATASET_CHOUETTE_IMPORT_KEY, String.class);
        Assertions.assertNotNull(originalImportKey);
        Assertions.assertEquals(importKey, originalImportKey);

    }


}
