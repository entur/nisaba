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

package no.entur.nisaba.routes.netex.export.notification;

import no.entur.nisaba.NisabaRouteBuilderIntegrationTestBase;
import no.entur.nisaba.TestApp;
import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.CURRENT_AGGREGATED_NETEX_FILENAME;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes =  TestApp.class)
class NetexExportNotificationQueueRouteBuilderTest extends NisabaRouteBuilderIntegrationTestBase {


    @Produce("entur-google-pubsub:NetexExportNotificationQueue")
    protected ProducerTemplate producerTemplate;

    @EndpointInject("mock:notifyConsumers")
    protected MockEndpoint notifyConsumers;




    @Test
    void testNetexExportNotification() throws Exception {

        //populate blob repo
        nisabaInMemoryBlobStoreRepository.uploadBlob(  BLOBSTORE_PATH_OUTBOUND+ "netex/" + "rb_avi-" + CURRENT_AGGREGATED_NETEX_FILENAME,
                getClass().getResourceAsStream("/no/entur/nisaba/netex/export/rb_avi-aggregated-netex.zip"),
                false);

        AdviceWith.adviceWith(context, "netex-export-notification-queue", a -> a.weaveByToUri("direct:notifyConsumers").replace().to("mock:notifyConsumers"));
        notifyConsumers.expectedMessageCount(1);

        context.start();

        producerTemplate.sendBody("rb_avi");

        notifyConsumers.assertIsSatisfied(2000000);

    }



}
