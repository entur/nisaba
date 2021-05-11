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
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.XML_NAMESPACE_NETEX;

/**
 * Publish service journeys to Kafka.
 */
@Component
public class NetexServiceJourneyPublicationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String COMMON_FILE = "COMMON_FILE";
    private static final String LINE_FILE = "LINE_FILE";
    private static final String SERVICE_JOURNEY_ID = "SERVICE_JOURNEY_ID";

    @Override
    public void configure() throws Exception {
        super.configure();

        from("google-pubsub:{{nisaba.pubsub.project.id}}:NetexServiceJourneyPublicationQueue?synchronousPull={{nisaba.pubsub.queue.servicejourney.synchronous:true}}")
                .to("direct:downloadNetexFile")
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

        from("direct:downloadNetexFile")
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx file ${body}")
                .setHeader(FILE_HANDLE, body())
                .to("direct:getNisabaBlob")
                .routeId("download-netex-file");

        from("direct:processCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processing common file ${header." + FILE_HANDLE + "}")
                .convertBodyTo(Document.class)
                .setHeader(COMMON_FILE, body())

                // do not split the common file for flexible lines
                .filter(header(Exchange.FILE_NAME).contains("_flexible_shared_data.xml"))
                .log(LoggingLevel.INFO, correlation() + "Processing common flexible line file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterCommonFlexibleLineFile.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed common flexible line file ${header." + FILE_HANDLE + "}")
                .stop()
                .end()

                // For other common files: remove scheduledStopPoints, stopAssignments, routePoints and serviceLinks and create separate PublicationDeliveries for each of them

                .setBody(header(COMMON_FILE))
                .log(LoggingLevel.INFO, correlation() + "Processing filtered common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterCommonFile.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed filtered common file ${header." + FILE_HANDLE + "}")

                .setBody(header(COMMON_FILE))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:scheduledStopPoints", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing scheduled stop points in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterScheduledStopPoint.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed scheduled stop points in common file ${header." + FILE_HANDLE + "}")
                .end()

                .setBody(header(COMMON_FILE))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:stopAssignments", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing stop assignments in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterStopAssignment.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed stop assignments in common file ${header." + FILE_HANDLE + "}")
                .end()

                .setBody(header(COMMON_FILE))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:routePoints", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing route points in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterRoutePoint.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed route points in common file ${header." + FILE_HANDLE + "}")
                .end()

                .setBody(header(COMMON_FILE))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:serviceLinks", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing service links in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterServiceLink.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed service links in common file ${header." + FILE_HANDLE + "}")
                .end()

                .log(LoggingLevel.INFO, correlation() + "Processed common file ${header." + FILE_HANDLE + "}")
                .routeId("process-common-file");

        from("direct:publishCommonFile")
                .marshal().zipFile()
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.common}}?clientId=nisaba-common&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy").id("to-kafka-topic-common")
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize common file ${header." + FILE_HANDLE + "} into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                .routeId("publish-common-file");

        from("direct:processLineFile")
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + FILE_HANDLE + "}")
                .convertBodyTo(Document.class)
                .setHeader(LINE_FILE, body())
                .split(xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney/@id", Constants.XML_NAMESPACE_NETEX))
                .to("direct:processServiceJourney")
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processed line file ${header." + FILE_HANDLE + "}")
                .routeId("process-line-file");

        from("direct:processServiceJourney")
                .setBody(simple("${body.value}"))
                .log(LoggingLevel.DEBUG, getClass().getName(), correlation() + "Processing ServiceJourney ${body}")
                .setHeader(SERVICE_JOURNEY_ID, body())
                .setBody(header(LINE_FILE))
                .to("xslt-saxon:filterServiceJourney.xsl")
                .setHeader(KafkaConstants.KEY, header(SERVICE_JOURNEY_ID))
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.servicejourney}}?clientId=nisaba-servicejourney&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&compressionCodec=gzip").id("to-kafka-topic-servicejourney")
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize service journey ${header." + SERVICE_JOURNEY_ID + "} in Line file ${header." + Exchange.FILE_NAME + "} into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                .routeId("process-service-journey");
    }

}
