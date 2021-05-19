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
import org.apache.camel.builder.FlexibleAggregationStrategy;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.time.LocalDateTime;
import java.util.TreeSet;

import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.XML_NAMESPACE_NETEX;

/**
 * Publish shared NeTEx objects (common files) to Kafka.
 */
@Component
public class NetexCommonFilePublicationRouteBuilder extends BaseRouteBuilder {

    private static final String COMMON_FILE = "COMMON_FILE";
    private static final String COMMON_FILE_PART = "COMMON_FILE_PART";
    private static final String COMMON_FILE_XSLT = "COMMON_FILE_XSLT";
    private static final int RANGE_SIZE = 1000;
    private static final String COMMON_FILE_NB_ITEMS = "COMMON_FILE_NB_ITEMS";
    private static final String SPLIT_LOWER_BOUND = "SPLIT_LOWER_BOUND";
    private static final String SPLIT_UPPER_BOUND = "SPLIT_UPPER_BOUND";
    private static final String NB_COMMON_FILES = "NB_COMMON_FILES";


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

                // Scheduled Stop Points

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Scheduled Stop Points"))
                .setHeader(COMMON_FILE_XSLT, constant("filterScheduledStopPoint.xsl"))
                .setHeader(COMMON_FILE_NB_ITEMS,
                        xpath("count(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:scheduledStopPoints/netex:ScheduledStopPoint)", Integer.class, XML_NAMESPACE_NETEX))
                .to("direct:splitCommonFile")

                // Stop Assignments

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Stop Assignments"))
                .setHeader(COMMON_FILE_XSLT, constant("filterStopAssignment.xsl"))
                .setHeader(COMMON_FILE_NB_ITEMS,
                        xpath("count(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:stopAssignments/netex:PassengerStopAssignment)", Integer.class, XML_NAMESPACE_NETEX))
                .to("direct:splitCommonFile")

                // Route Points

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Route Points"))
                .setHeader(COMMON_FILE_XSLT, constant("filterRoutePoint.xsl"))
                .setHeader(COMMON_FILE_NB_ITEMS,
                        xpath("count(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:routePoints/netex:RoutePoint)", Integer.class, XML_NAMESPACE_NETEX))
                .to("direct:splitCommonFile")

                // Service Links

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Service Links"))
                .setHeader(COMMON_FILE_XSLT, constant("filterServiceLink.xsl"))
                .setHeader(COMMON_FILE_NB_ITEMS,
                        xpath("count(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:serviceLinks/netex:ServiceLink)", Integer.class, XML_NAMESPACE_NETEX))
                .to("direct:splitCommonFile")

                .log(LoggingLevel.INFO, correlation() + "Processed common file ${header." + FILE_HANDLE + "}")
                .routeId("process-common-file");

        // split items in the common files in smaller PublicationDeliveries so that each message does not exceed the maximum size of a Kafka record
        from("direct:splitCommonFile")
                .filter(header(COMMON_FILE_NB_ITEMS).isGreaterThan(0))
                .log(LoggingLevel.INFO, correlation() + "Processing ${header." + COMMON_FILE_PART + "} in common file ${header." + FILE_HANDLE + "}")
                .bean(new ListRangeSplitter(RANGE_SIZE), "split(${header." + COMMON_FILE_NB_ITEMS + "})")
                .log(LoggingLevel.INFO, correlation() + "Splitting ${header." + COMMON_FILE_NB_ITEMS + "} ${header." + COMMON_FILE_PART + "} into ${body.size} PublicationDeliveries")
                .split(body()).aggregationStrategy(new FlexibleAggregationStrategy<LocalDateTime>()
                .storeInHeader(NB_COMMON_FILES)
                .pick(header(NB_COMMON_FILES)))
                .log(LoggingLevel.INFO, correlation() + "Processing ${header." + COMMON_FILE_PART + "} from position ${body.lowerBound} to position ${body.upperBound}")
                .setHeader(SPLIT_LOWER_BOUND, simple("${body.lowerBound}"))
                .setHeader(SPLIT_UPPER_BOUND, simple("${body.upperBound}"))
                .setBody(header(COMMON_FILE))
                .toD("xslt-saxon:${header." + COMMON_FILE_XSLT + "}")
                .to("direct:publishCommonFile")
                // end split
                .end()

                .log(LoggingLevel.INFO, correlation() + "Processed ${header." + COMMON_FILE_PART + "} in common file ${header." + FILE_HANDLE + "}")
                // end filter
                .end()
                .routeId("split-common-file");

        from("direct:publishCommonFile")
                .marshal().zipFile()
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.common}}?clientId=nisaba-common&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&valueSerializer=org.apache.kafka.common.serialization.ByteArraySerializer").id("to-kafka-topic-common")
                .process(e -> e.getIn().setHeader(NB_COMMON_FILES, e.getIn().getHeader(NB_COMMON_FILES, 0, Integer.class) + 1))
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize common file ${header." + FILE_HANDLE + "} (${header." + COMMON_FILE_PART + "}) into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                .routeId("publish-common-file");

    }

}
