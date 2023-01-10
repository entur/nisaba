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
import no.entur.nisaba.netex.JourneyPatternReferencedEntities;
import no.entur.nisaba.netex.LineReferencedEntities;
import no.entur.nisaba.netex.PublicationDeliveryBuilder;
import no.entur.nisaba.netex.RouteReferencedEntities;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.converter.jaxb.JaxbDataFormat;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.entur.netex.NetexParser;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.ServiceJourney;
import org.springframework.stereotype.Component;

import javax.xml.bind.JAXBContext;
import java.time.LocalDateTime;
import java.util.List;

import static no.entur.nisaba.Constants.COMMON_FILE_INDEX;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.DATASET_IMPORT_KEY;
import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.NETEX_FILE_NAME;
import static no.entur.nisaba.Constants.PUBLICATION_DELIVERY_TIMESTAMP;

/**
 * Publish service journeys to Kafka.
 */
@Component
public class NetexServiceJourneyPublicationRouteBuilder extends BaseRouteBuilder {

    private static final String LINE_FILE = "LINE_FILE";

    private static final String JOURNEY_PATTERNS = "JOURNEY_PATTERNS";
    private static final String SERVICE_JOURNEYS = "SERVICE_JOURNEYS";

    @Override
    public void configure() throws Exception {
        super.configure();

        JAXBContext context = JAXBContext
                .newInstance(PublicationDeliveryStructure.class);
        JaxbDataFormat xmlDataFormat = new JaxbDataFormat();
        xmlDataFormat.setPrettyPrint(false);
        xmlDataFormat.setContext(context);

        from("google-pubsub:{{nisaba.pubsub.project.id}}:NetexServiceJourneyPublicationQueue?synchronousPull={{nisaba.pubsub.queue.servicejourney.synchronous:true}}")
                .streamCaching()
                .log(LoggingLevel.INFO, correlation() + "Processing file ${body}")
                .setHeader(NETEX_FILE_NAME, body())

                .to("direct:downloadNetexFile")
                .filter(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "Cannot find line file ${header." + FILE_HANDLE + "} in the blob store")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + FILE_HANDLE + "}")
                .unmarshal().zipFile()
                .setHeader(LINE_FILE, body())
                .setHeader(Constants.LINE_FILE_INDEX, method(NetexParser.class, "parse(${body})"))
                .log(LoggingLevel.DEBUG, correlation() + "Parsed line file")

                .to("direct:downloadCommonFiles")
                .log(LoggingLevel.DEBUG, correlation() + "Parsed common files")

                .to("direct:processLine")

                .routeId("pubsub-process-service-journey");

        from("direct:downloadNetexFile")
                .setHeader(FILE_HANDLE, body())
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx file ${header." + FILE_HANDLE + "}")
                .to("direct:getNisabaBlob")
                .routeId("download-netex-file");

        from("direct:downloadCommonFiles")

                // downloading the common file for flexible lines
                .setHeader(FILE_HANDLE, simple("${header." + DATASET_IMPORT_KEY + "}/_${header." + DATASET_CODESPACE + ".toUpperCase()}_flexible_shared_data.xml.zip"))
                .log(LoggingLevel.INFO, correlation() + "Downloading Common file ${header." + FILE_HANDLE + "}")
                .to("direct:getNisabaBlob")
                .filter(body().isNotNull())
                .unmarshal().zipFile()
                .setHeader(Constants.COMMON_FILE_INDEX, method(NetexParser.class, "parse(${body})"))
                .log(LoggingLevel.DEBUG, correlation() + "Parsed common file ${header." + FILE_HANDLE + "}")
                // end filter
                .end()

                // downloading the common file for non-flexible lines and merge with index for flexible lines.
                // if entities in the two files have the same id, the entities from the non-flexible lines (chouette) will overwrite those from the flexible lines (uttu)
                .setHeader(FILE_HANDLE, simple("${header." + DATASET_IMPORT_KEY + "}/_${header." + DATASET_CODESPACE + ".toUpperCase()}_shared_data.xml.zip"))
                .log(LoggingLevel.INFO, correlation() + "Downloading Common file ${header." + FILE_HANDLE + "}")
                .to("direct:getNisabaBlob")
                .filter(body().isNotNull())
                .unmarshal().zipFile()
                .choice()
                .when(header(COMMON_FILE_INDEX).isNotNull())
                .setHeader(Constants.COMMON_FILE_INDEX, method(NetexParser.class, "parse(${body}, ${header." + COMMON_FILE_INDEX + "})"))
                .log(LoggingLevel.DEBUG, correlation() + "Aggregated common file ${header." + FILE_HANDLE + "} to previous index")
                .otherwise()
                .setHeader(Constants.COMMON_FILE_INDEX, method(NetexParser.class, "parse(${body})"))
                .log(LoggingLevel.DEBUG, correlation() + "Created new index for common file ${header." + FILE_HANDLE + "}")
                // end choice
                .end()
                .log(LoggingLevel.DEBUG, correlation() + "Parsed common file ${header." + FILE_HANDLE + "}")
                //end filter
                .end()


                .routeId("download-common-files");

        from("direct:processLine")
                .setHeader(PUBLICATION_DELIVERY_TIMESTAMP, LocalDateTime::now)
                .process(exchange -> {
                    NetexEntitiesIndex lineNetexEntitiesIndex = exchange.getIn().getHeader(Constants.LINE_FILE_INDEX, NetexEntitiesIndex.class);
                    NetexEntitiesIndex commonNetexEntitiesIndex = exchange.getIn().getHeader(Constants.COMMON_FILE_INDEX, NetexEntitiesIndex.class);
                    LineReferencedEntities lineReferencedEntities = new LineReferencedEntities.LineReferencedEntitiesBuilder()
                            .withNetexLineEntitiesIndex(lineNetexEntitiesIndex)
                            .withNetexCommonEntitiesIndex(commonNetexEntitiesIndex)
                            .build();
                    exchange.getIn().setHeader(Constants.LINE_REFERENCES, lineReferencedEntities);
                })
                .split(simple("${header." + Constants.LINE_FILE_INDEX + ".routeIndex.all}"))
                .to("direct:processRoute")
                .end()
                .routeId("process-line");

        from("direct:processRoute")
                .log(LoggingLevel.DEBUG, correlation() + "Processing Route ${body.id}")
                .process(exchange -> {
                    Route route = exchange.getIn().getBody(Route.class);
                    NetexEntitiesIndex netexLineEntitiesIndex = exchange.getIn().getHeader(Constants.LINE_FILE_INDEX, NetexEntitiesIndex.class);
                    NetexEntitiesIndex commonNetexEntitiesIndex = exchange.getIn().getHeader(Constants.COMMON_FILE_INDEX, NetexEntitiesIndex.class);
                    List<JourneyPattern> journeyPatterns = netexLineEntitiesIndex.getJourneyPatternIndex().getAll().stream().filter(journeyPattern -> journeyPattern.getRouteRef().getRef().equals(route.getId())).toList();
                    exchange.getIn().setHeader(JOURNEY_PATTERNS, journeyPatterns);
                    RouteReferencedEntities routeReferencedEntities = new RouteReferencedEntities.RouteReferencedEntitiesBuilder()
                            .withRoute(route)
                            .withNetexCommonEntitiesIndex(commonNetexEntitiesIndex)
                            .build();
                    exchange.getIn().setHeader(Constants.ROUTE_REFERENCES, routeReferencedEntities);
                })
                .split(simple("${header." + JOURNEY_PATTERNS + "}"))
                .to("direct:processJourneyPattern")
                .routeId("process-route");

        from("direct:processJourneyPattern")
                .log(LoggingLevel.DEBUG, correlation() + "Processing JourneyPattern ${body.id}")
                .process(exchange -> {
                    JourneyPattern journeyPattern = exchange.getIn().getBody(JourneyPattern.class);
                    NetexEntitiesIndex netexLineEntitiesIndex = exchange.getIn().getHeader(Constants.LINE_FILE_INDEX, NetexEntitiesIndex.class);
                    NetexEntitiesIndex commonNetexEntitiesIndex = exchange.getIn().getHeader(Constants.COMMON_FILE_INDEX, NetexEntitiesIndex.class);
                    List<ServiceJourney> serviceJourneys = netexLineEntitiesIndex.getServiceJourneyIndex().getAll().stream().filter(serviceJourney -> serviceJourney.getJourneyPatternRef().getValue().getRef().equals(journeyPattern.getId())).toList();
                    exchange.getIn().setHeader(SERVICE_JOURNEYS, serviceJourneys);
                    JourneyPatternReferencedEntities journeyPatternReferencedEntities = new JourneyPatternReferencedEntities.JourneyPatternReferencedEntitiesBuilder()
                            .withJourneyPattern(journeyPattern)
                            .withNetexCommonEntitiesIndex(commonNetexEntitiesIndex)
                            .withNetexLineEntitiesIndex(netexLineEntitiesIndex)
                            .build();
                    exchange.getIn().setHeader(Constants.JOURNEY_PATTERN_REFERENCES, journeyPatternReferencedEntities);
                })
                .split(simple("${header." + SERVICE_JOURNEYS + "}"))
                .to("direct:processServiceJourney")
                .routeId("process-journey-pattern");

        from("direct:processServiceJourney")
                .streamCaching()
                .log(LoggingLevel.DEBUG, getClass().getName(), correlation() + "Processing ServiceJourney ${body.id}")
                // extend pubsub acknowledgment deadline every 500 service journeys
                .filter(exchange -> exchange.getProperty(Exchange.SPLIT_INDEX, Integer.class) % 500 == 0)
                .log(LoggingLevel.DEBUG, getClass().getName(), correlation() + "Extending PubSub ack deadline for file ${header." + NETEX_FILE_NAME + "}")
                .process(this::extendAckDeadline)
                //end filter
                .end()
                .setHeader(Constants.SERVICE_JOURNEY_ID, simple("${body.id}"))
                .bean(PublicationDeliveryBuilder.class, "build")
                .marshal(xmlDataFormat)
                .setHeader(KafkaConstants.KEY, header(Constants.SERVICE_JOURNEY_ID))
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.servicejourney}}?clientId=nisaba-servicejourney&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&compressionCodec=gzip").id("to-kafka-topic-servicejourney")
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize service journey ${header." + Constants.SERVICE_JOURNEY_ID + "} in Line file ${header." + NETEX_FILE_NAME + "} into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                .routeId("process-service-journey");
    }


}
