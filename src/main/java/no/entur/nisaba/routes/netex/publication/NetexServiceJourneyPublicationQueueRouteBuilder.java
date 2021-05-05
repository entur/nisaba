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

package no.entur.nisaba.routes.netex.publication;

import no.entur.nisaba.Constants;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.component.kafka.KafkaConstants;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import static no.entur.nisaba.Constants.FILE_HANDLE;

/**
 * Publish service journeys to Kafka.
 */
@Component
public class NetexServiceJourneyPublicationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String LINE_FILE = "LINE_FILE";
    private static final String SERVICE_JOURNEY_ID = "SERVICE_JOURNEY_ID";

    @Override
    public void configure() throws Exception {
        super.configure();

        from("google-pubsub:{{spring.cloud.gcp.pubsub.project-id}}:NetexServiceJourneyPublicationQueue?synchronousPull=true")
                .to("direct:downloadNetexLineFile")
                .filter(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "Cannot find line file ${header." + FILE_HANDLE + "} in the blob store")
                .stop()
                .end()
                .unmarshal().zipFile()
                .choice()
                .when(header(Exchange.FILE_NAME).startsWith("_"))
                .to("direct:processCommonFile")
                .otherwise()
                .to("direct:processLineFile")
                .routeId("pubsub-process-service-journey");

        from("direct:downloadNetexLineFile")
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx line file")
                .setHeader(FILE_HANDLE, body())
                .to("direct:getNisabaBlob")
                .routeId("download-netex-line-file");

        from("direct:processCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processing common file ${header." + Exchange.FILE_NAME + "}")
                .to("xslt:filterServiceLinks.xsl")
                .marshal().zipFile()
                .to("kafka:{{nisaba.kafka.topic.common}}?clientId=nisaba-common&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy")
                .routeId("process-common-file");

        from("direct:processLineFile")
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + Exchange.FILE_NAME + "}")
                .convertBodyTo(Document.class)
                .setHeader(LINE_FILE, body())
                .split(xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney/@id", Constants.XML_NAMESPACE_NETEX))
                .to("direct:processServiceJourney")
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processed line file ${header." + Exchange.FILE_NAME + "}")
                .routeId("process-line-file");

        from("direct:processServiceJourney")
                .setBody(simple("${body.value}"))
                .log(LoggingLevel.DEBUG, getClass().getName(), correlation() + "Processing ServiceJourney ${body}")
                .setHeader(SERVICE_JOURNEY_ID, body())
                .setBody(header(LINE_FILE))
                .to("xslt:filterServiceJourney.xsl")
                .setHeader(KafkaConstants.KEY, header(SERVICE_JOURNEY_ID))
                .to("kafka:{{nisaba.kafka.topic.servicejourney}}?clientId=nisaba-servicejourney&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&compressionCodec=gzip")
                .routeId("process-service-journey");
    }

}
