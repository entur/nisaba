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

import no.entur.nisaba.domain.ListRangeSplitter;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.XML_NAMESPACE_NETEX;

/**
 * Publish service journeys to Kafka.
 */
@Component
public class NetexCommonFilePublicationRouteBuilder extends BaseRouteBuilder {

    private static final String COMMON_FILE = "COMMON_FILE";
    private static final String COMMON_FILE_PART = "COMMON_FILE_PART";
    private static final int RANGE_SIZE = 1000;
    private static final String NB_SERVICE_LINKS = "NB_SERVICE_LINKS";
    private static final String SPLIT_LOWER_BOUND = "SPLIT_LOWER_BOUND";
    private static final String SPLIT_UPPER_BOUND = "SPLIT_UPPER_BOUND";

    @Override
    public void configure() throws Exception {
        super.configure();


        from("direct:processCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processing common file ${header." + FILE_HANDLE + "}")
                .convertBodyTo(Document.class)
                .setHeader(COMMON_FILE, body())

                // do not split the common file for flexible lines
                .filter(header(Exchange.FILE_NAME).contains("_flexible_shared_data.xml"))
                .log(LoggingLevel.INFO, correlation() + "Processing common flexible line file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterCommonFlexibleLineFile.xsl")
                .setHeader(COMMON_FILE_PART, constant("Flexible lines"))
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed common flexible line file ${header." + FILE_HANDLE + "}")
                .stop()
                .end()

                // For other common files: remove scheduledStopPoints, stopAssignments, routePoints and serviceLinks and create separate PublicationDeliveries for each of them

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Filtered common file"))
                .log(LoggingLevel.INFO, correlation() + "Processing filtered common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterCommonFile.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed filtered common file ${header." + FILE_HANDLE + "}")

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Stop Points"))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:scheduledStopPoints", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing scheduled stop points in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterScheduledStopPoint.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed scheduled stop points in common file ${header." + FILE_HANDLE + "}")
                .end()

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Stop Assignments"))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:stopAssignments", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing stop assignments in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterStopAssignment.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed stop assignments in common file ${header." + FILE_HANDLE + "}")
                .end()

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Route Points"))
                .filter().xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:routePoints", XML_NAMESPACE_NETEX)
                .log(LoggingLevel.INFO, correlation() + "Processing route points in common file ${header." + FILE_HANDLE + "}")
                .to("xslt-saxon:filterRoutePoint.xsl")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed route points in common file ${header." + FILE_HANDLE + "}")
                .end()

                // for Service Links: split in several PublicationDeliveries to avoid producing Kafka messages larger than the maximum message size

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Service Links"))
                .setHeader(NB_SERVICE_LINKS,
                        xpath("count(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:serviceLinks/netex:ServiceLink)", Integer.class, XML_NAMESPACE_NETEX))
                .filter(header(NB_SERVICE_LINKS).isGreaterThan(0))
                .log(LoggingLevel.INFO, correlation() + "Processing service links in common file ${header." + FILE_HANDLE + "}")
                .bean(new ListRangeSplitter(RANGE_SIZE), "split(${header.NB_SERVICE_LINKS})")
                .log(LoggingLevel.INFO, correlation() + "Splitting ${header.NB_SERVICE_LINKS} service links into ${body.size} PublicationDeliveries")
                .to("direct:splitServiceLinks")
                .log(LoggingLevel.INFO, correlation() + "Processed service links in common file ${header." + FILE_HANDLE + "}")
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processed common file ${header." + FILE_HANDLE + "}")
                .routeId("process-common-file");

        from("direct:splitServiceLinks")
                .split(body())
                .log(LoggingLevel.INFO, correlation() + "Processing service links from position ${body.lowerBound} to position ${body.upperBound}")
                .setHeader(SPLIT_LOWER_BOUND, simple("${body.lowerBound}"))
                .setHeader(SPLIT_UPPER_BOUND, simple("${body.upperBound}"))
                .setBody(header(COMMON_FILE))
                .to("xslt-saxon:filterServiceLink.xsl")
                .to("file:/tmp/camel?fileName=${date:now:yyyyMMddHHmmssSSS}.xml")
                .to("direct:publishCommonFile")
                .routeId("split-service-links");

        from("direct:publishCommonFile")
                .marshal().zipFile()
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.common}}?clientId=nisaba-common&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy").id("to-kafka-topic-common")
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize common file ${header." + FILE_HANDLE + "} (${header." + COMMON_FILE_PART + "}) into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                .routeId("publish-common-file");

    }

}
