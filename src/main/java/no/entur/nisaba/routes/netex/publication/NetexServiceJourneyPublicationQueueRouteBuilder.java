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
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.bind.JAXBContext;
import java.io.InputStream;

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
                .to("direct:processLineFile")
                .routeId("pubsub-process-service-journey");

        from("direct:downloadNetexFile")
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx file ${body}")
                .setHeader(FILE_HANDLE, body())
                .to("direct:getNisabaBlob")
                .routeId("download-netex-file");

        from("direct:downloadCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Downloading Common file ${body}")
                .setHeader(FILE_HANDLE, constant("_AVI_shared_data.xml.zip"))
                .to("direct:getNisabaBlob")
                .routeId("download-common-file");

        from("direct:processLineFile")
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + FILE_HANDLE + "}")
                .convertBodyTo(Document.class)
                .setHeader(LINE_FILE, body())
                .convertBodyTo(String.class)
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
                .setBody(header(LINE_FILE))
                .split(xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney/@id", Constants.XML_NAMESPACE_NETEX))
                .to("direct:processServiceJourney")
                .end()
                .log(LoggingLevel.INFO, correlation() + "Processed line file ${header." + FILE_HANDLE + "}")
                .routeId("process-line-file");

        JAXBContext context = JAXBContext
                .newInstance(PublicationDeliveryStructure.class);
        JaxbDataFormat xmlDataFormat = new JaxbDataFormat();
        xmlDataFormat.setContext(context);

        from("direct:processServiceJourney")
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
