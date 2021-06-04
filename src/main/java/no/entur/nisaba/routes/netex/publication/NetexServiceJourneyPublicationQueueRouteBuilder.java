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
import org.apache.camel.converter.jaxb.JaxbDataFormat;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.entur.netex.NetexParser;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.entur.netex.loader.NetexXmlParser;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.ServiceJourney;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.bind.JAXBContext;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.FILE_HANDLE;

/**
 * Publish service journeys to Kafka.
 */
@Component
public class NetexServiceJourneyPublicationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String LINE_FILE = "LINE_FILE";
    private static final String SERVICE_JOURNEY_ID = "SERVICE_JOURNEY_ID";
    private static final String COMMON_FILE_INDEX = "COMMON_FILE_INDEX";
    private static final String LINE_FILE_INDEX = "LINE_FILE_INDEX";
    private static final String JOURNEY_PATTERNS = "JOURNEY_PATTERNS";
    private static final String SERVICE_JOURNEYS = "SERVICE_JOURNEYS";

    @Override
    public void configure() throws Exception {
        super.configure();

        JAXBContext context = JAXBContext
                .newInstance(PublicationDeliveryStructure.class);
        JaxbDataFormat xmlDataFormat = new JaxbDataFormat();
        xmlDataFormat.setContext(context);

        from("google-pubsub:{{nisaba.pubsub.project.id}}:NetexServiceJourneyPublicationQueue?synchronousPull={{nisaba.pubsub.queue.servicejourney.synchronous:true}}")
                .to("direct:downloadNetexFile")
                .filter(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "Cannot find line file ${header." + FILE_HANDLE + "} in the blob store")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + FILE_HANDLE + "}")
                .unmarshal().zipFile()
                .process(exchange -> {
                    NetexParser parser = new NetexParser();
                    NetexEntitiesIndex index = parser.parse(exchange.getIn().getBody(InputStream.class));
                    exchange.getIn().setHeader(LINE_FILE_INDEX, index);
                    log.info("Parsed line file");
                })
                .to("direct:downloadCommonFile")
                .unmarshal().zipFile()
                .convertBodyTo(InputStream.class)
                .process(exchange -> {
                    NetexParser parser = new NetexParser();
                    NetexEntitiesIndex index = parser.parse(exchange.getIn().getBody(InputStream.class));
                    exchange.getIn().setHeader(COMMON_FILE_INDEX, index);
                    log.info("Parsed common file");
                })
                .to("direct:processLineFile")
                .routeId("pubsub-process-service-journey");

        from("direct:downloadNetexFile")
                .setHeader(FILE_HANDLE, body())
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx file ${header." + FILE_HANDLE + "}")
                .to("direct:getNisabaBlob")
                .routeId("download-netex-file");

        from("direct:downloadCommonFile")
                .setHeader(FILE_HANDLE, simple("_${header." + DATASET_CODESPACE + ".toUpperCase()}_shared_data.xml.zip"))
                .log(LoggingLevel.INFO, correlation() + "Downloading Common file ${header." + FILE_HANDLE + "}")
                .to("direct:getNisabaBlob")
                .routeId("download-common-file");

        from("direct:processLineFile")
                    .split(simple("${header." + LINE_FILE_INDEX + ".routeIndex.all}"))
                .to("direct:processRoute")
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processed line file ${header." + FILE_HANDLE + "}")
                .routeId("process-line-file");

        from("direct:processRoute")
                .log(LoggingLevel.INFO, correlation() + "Processing route ${body.id}")
                .process(exchange -> {
                    Route route = exchange.getIn().getBody(Route.class);
                    NetexEntitiesIndex netexEntitiesIndex = exchange.getIn().getHeader(LINE_FILE_INDEX, NetexEntitiesIndex.class);
                    NetexEntitiesIndex commonNetexEntitiesIndex = exchange.getIn().getHeader(COMMON_FILE_INDEX, NetexEntitiesIndex.class);
                    List<JourneyPattern> journeyPatterns = netexEntitiesIndex.getJourneyPatternIndex().getAll().stream().filter(journeyPattern -> journeyPattern.getRouteRef().getRef().equals(route.getId())).collect(Collectors.toList());
                    exchange.getIn().setHeader(JOURNEY_PATTERNS, journeyPatterns);
                    RouteReferencedEntities routeReferencedEntities = new RouteReferencedEntities(route,commonNetexEntitiesIndex);
                    exchange.getIn().setHeader("ROUTE_REFERENCES", routeReferencedEntities);
                })
                .split(simple("${header." + JOURNEY_PATTERNS + "}"))
                .to("direct:processJourneyPattern")
                .routeId("process-route");

        from("direct:processJourneyPattern")
                .log(LoggingLevel.INFO, correlation() + "Processing journey pattern ${body.id}")
                .process(exchange -> {
                    JourneyPattern journeyPattern = exchange.getIn().getBody(JourneyPattern.class);
                    NetexEntitiesIndex netexEntitiesIndex = exchange.getIn().getHeader(LINE_FILE_INDEX, NetexEntitiesIndex.class);
                    List<ServiceJourney> serviceJourneys = netexEntitiesIndex.getServiceJourneyIndex().getAll().stream().filter(serviceJourney -> serviceJourney.getJourneyPatternRef().getValue().getRef().equals(journeyPattern.getId())).collect(Collectors.toList());
                    exchange.getIn().setHeader(SERVICE_JOURNEYS, serviceJourneys);
                })
                .split(simple("${header." + SERVICE_JOURNEYS + "}"))
                .to("direct:processServiceJourney")
                .routeId("process-journey-pattern");

        from("direct:processServiceJourney")
                .log(LoggingLevel.INFO, correlation() + "Processing service journey ${body.id}")
                // extend pubsub acknowledgment deadline every 500 service journeys
                .filter(exchange -> exchange.getProperty(Exchange.SPLIT_INDEX, Integer.class) % 500 == 0)
                .process(this::extendAckDeadline)
                //end filter
                .end()
                .setBody(simple("${body.value}"))
                .log(LoggingLevel.DEBUG, getClass().getName(), correlation() + "Processing ServiceJourney ${body}")
                .setHeader(SERVICE_JOURNEY_ID, body())
                .setBody(header(LINE_FILE))
                .to("xslt-saxon:filterServiceJourney.xsl")
                .to("file:/tmp/camel/servicejourney?fileName=${date:now:yyyyMMddHHmmssSSS}.xml")
                .convertBodyTo(String.class)
                .process(exchange -> {
                    NetexXmlParser netexXmlParser = new NetexXmlParser();
                    PublicationDeliveryStructure publicationDeliveryStructure = netexXmlParser.parseXmlDoc(exchange.getIn().getBody(InputStream.class));
                    exchange.getIn().setHeader("PUBLICATION_DELIVERY", publicationDeliveryStructure);
                })
                .bean(PublicationDeliveryUpdater.class, "update")
                .setBody(header("PUBLICATION_DELIVERY"))
                .marshal(xmlDataFormat)
                .to("file:/tmp/camel/servicejourney?fileName=${date:now:yyyyMMddHHmmssSSS}-transformed.xml")

                .process(exchange -> System.out.println("aa"))
                .setHeader(KafkaConstants.KEY, header(SERVICE_JOURNEY_ID))
                // explicitly compress the payload due to https://issues.apache.org/jira/browse/KAFKA-4169
                .marshal().zipFile()
                .doTry()
                .to("kafka:{{nisaba.kafka.topic.servicejourney}}?clientId=nisaba-servicejourney&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy").id("to-kafka-topic-servicejourney")
                .doCatch(RecordTooLargeException.class)
                .log(LoggingLevel.ERROR, "Cannot serialize service journey ${header." + SERVICE_JOURNEY_ID + "} in Line file ${header." + Exchange.FILE_NAME + "} into Kafka topic, max message size exceeded ${exception.stacktrace} ")
                .stop()
                .routeId("process-service-journey");
    }


}
