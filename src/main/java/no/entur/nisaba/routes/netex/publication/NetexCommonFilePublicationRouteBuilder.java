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
import no.entur.nisaba.domain.ListRangeSplitter;
import no.entur.nisaba.event.DatasetStatHelper;
import no.entur.nisaba.netex.NoticeAndDestinationDisplayPublicationDeliveryBuilder;
import no.entur.nisaba.netex.ServiceLinkPublicationDeliveryBuilder;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.LoggingLevel;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.entur.netex.NetexParser;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.ServiceLink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static no.entur.nisaba.Constants.COMMON_FILE_INDEX;
import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.NETEX_FILE_NAME;


/**
 * Publish shared NeTEx objects (common files) to Kafka.
 */
@Component
public class NetexCommonFilePublicationRouteBuilder extends BaseRouteBuilder {

    private static final String COMMON_FILE = "COMMON_FILE";
    private static final String COMMON_FILE_PART = "COMMON_FILE_PART";
    private static final String NB_SERVICE_LINKS = "COMMON_FILE_NB_ITEMS";
    private static final String COMMON_FILE_RANGE_SIZE = "COMMON_FILE_RANGE_SIZE";

    private final int rangeSizeForServiceLinks;

    public NetexCommonFilePublicationRouteBuilder(@Value("${nisaba.netex.service-links.range-size:300}") int rangeSizeForServiceLinks) {
        this.rangeSizeForServiceLinks = rangeSizeForServiceLinks;
    }

    @Override
    public void configure() throws Exception {
        super.configure();


        // common files are processed synchronously (in the same thread as the one processing the notification event)
        // so that the number of common files is known before sending the notification event.
        from("direct:processCommonFile")
                .streamCaching()
                .log(LoggingLevel.INFO, correlation() + "Processing common file ${header." + NETEX_FILE_NAME + "}")
                .setHeader(COMMON_FILE, body())
                .setHeader(Constants.COMMON_FILE_INDEX, method(NetexParser.class, "parse(${body})"))

                // Publish a common file containing all notices and destination displays

                .setBody(header(COMMON_FILE))
                .setHeader(COMMON_FILE_PART, constant("Filtered common file"))
                .log(LoggingLevel.INFO, correlation() + "Processing filtered common file ${header." + NETEX_FILE_NAME + "}")
                .bean(NoticeAndDestinationDisplayPublicationDeliveryBuilder.class, "build")
                .marshal("netexJaxbDataFormat")
                .to("direct:publishCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processed filtered common file ${header." + NETEX_FILE_NAME + "}")

                // Publish common files containing service links

                .setBody(header(COMMON_FILE))
                .process( exchange -> {
                    List<ServiceLink> serviceLinks = new ArrayList<>(exchange.getIn().getHeader(COMMON_FILE_INDEX, NetexEntitiesIndex.class).getServiceLinkIndex().getAll());
                    exchange.getIn().setHeader(Constants.SERVICE_LINKS, serviceLinks);
                })
                .setHeader(NB_SERVICE_LINKS, simple("${header." + Constants.SERVICE_LINKS + ".size}"))
                .setHeader(COMMON_FILE_PART, constant("Service Links"))
                .setHeader(COMMON_FILE_RANGE_SIZE, constant(rangeSizeForServiceLinks))
                .to("direct:splitServiceLinks")
                .log(LoggingLevel.INFO, correlation() + "Processed common file ${header." + NETEX_FILE_NAME + "}")
                .routeId("process-common-file");

        // split ServiceLinks in the common files in smaller PublicationDeliveries so that each message does not exceed the maximum size of a Kafka record
        from("direct:splitServiceLinks")
                .filter(header(NB_SERVICE_LINKS).isGreaterThan(0))
                .log(LoggingLevel.INFO, correlation() + "Processing ServiceLinks in common file ${header." + NETEX_FILE_NAME + "}")
                .bean(new ListRangeSplitter(), "split(${header." + NB_SERVICE_LINKS + "}, ${header." + COMMON_FILE_RANGE_SIZE + "})")
                .log(LoggingLevel.INFO, correlation() + "Splitting ${header." + NB_SERVICE_LINKS + "} ServiceLinks into ${body.size} PublicationDeliveries")
                .split(body())
                .log(LoggingLevel.INFO, correlation() + "Processing ServiceLinks from position ${body.lowerBound} to position ${body.upperBound}")
                .setHeader(Constants.SERVICE_LINKS_SUB_LIST, simple("${header." + Constants.SERVICE_LINKS + ".subList(${body.lowerBound},${body.upperBound})}"))
                .setBody(header(COMMON_FILE))
                .bean(ServiceLinkPublicationDeliveryBuilder.class, "build")
                .marshal("netexJaxbDataFormat")
                .to("direct:publishCommonFile")
                // end split
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processed ServiceLinks in common file ${header." + FILE_HANDLE + "}")
                // end filter
                .end()
                .routeId("split-service-links");

        from("direct:publishCommonFile")
                .streamCaching()
                .filter(simple("${properties:nisaba.publish.enabled:true}"))
                // explicitly compress the payload due to https://issues.apache.org/jira/browse/KAFKA-4169
                .marshal().zipFile()
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.common}}?clientId=nisaba-common&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&valueSerializer=org.apache.kafka.common.serialization.ByteArraySerializer").id("to-kafka-topic-common")
                .bean(DatasetStatHelper.class, "addCommonFiles(1)")
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize common file ${header." + FILE_HANDLE + "} (${header." + COMMON_FILE_PART + "}) into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                // end filter
                .end()
                .routeId("publish-common-file");

    }
}
