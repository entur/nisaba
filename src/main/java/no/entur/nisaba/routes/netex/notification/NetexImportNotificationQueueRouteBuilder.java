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
import no.entur.nisaba.event.NetexImportEventFactory;
import no.entur.nisaba.event.NetexImportEventKeyFactory;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.FlexibleAggregationStrategy;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.dataformat.zipfile.ZipSplitter;
import org.apache.camel.support.builder.Namespaces;
import org.apache.camel.support.builder.PredicateBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.TreeSet;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.DATASET_CREATION_TIME;
import static no.entur.nisaba.Constants.DATASET_IMPORT_KEY;
import static no.entur.nisaba.Constants.FILE_HANDLE;
import static org.apache.camel.builder.Builder.bean;

/**
 * Receive a notification when a new NeTEx export is available in the blob store and send an event in a Kafka topic
 * if this is the first time the dataset is published.
 */
@Component
public class NetexImportNotificationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String EXPORT_FILE_NAME = "netex/rb_${body}-" + Constants.CURRENT_AGGREGATED_NETEX_FILENAME;
    public static final Namespaces XML_NAMESPACE_NETEX = new Namespaces("netex", "http://www.netex.org.uk/netex");
    private static final String LINE_FILE = "LINE_FILE";
    private static final String SERVICE_JOURNEY_ID = "SERVICE_JOURNEY_ID";

    @Override
    public void configure() throws Exception {
        super.configure();


        singletonFrom("entur-google-pubsub:NetexExportNotificationQueue")

                .process(this::setCorrelationIdIfMissing)
                .setHeader(DATASET_CODESPACE, bodyAs(String.class))
                .log(LoggingLevel.INFO, correlation() + "Received NeTEx export notification")
                .to("direct:downloadNetexDataset")
                .choice()
                .when(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "NeTEx export file not found")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "NeTEx export file downloaded")
                .to("direct:retrieveDatasetCreationTime")
                .bean(NetexImportEventFactory.class, "createNetexImportEvent")
                .setHeader(DATASET_IMPORT_KEY, bean(NetexImportEventKeyFactory.class, "createNetexImportEventKey"))
                .to("direct:notifyConsumersIfNew")
                .routeId("netex-export-notification-queue");

        from("direct:downloadNetexDataset")
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx dataset")
                .setHeader(FILE_HANDLE, simple(BLOBSTORE_PATH_OUTBOUND + EXPORT_FILE_NAME))
                .to("direct:getBlob")
                .routeId("download-netex-dataset");

        // Iterate over every XML files in the NeTEx archive, parse the "created" attribute of the CompositeFrame,
        // convert it to a DateTime object and accumulate it in a SortedSet.
        // At the end of the iteration, the last element in the SortedSet contains the most recent of the creation dates.
        from("direct:retrieveDatasetCreationTime")
                .log(LoggingLevel.INFO, correlation() + "Retrieving dataset creation time")
                .split(new ZipSplitter()).aggregationStrategy(new FlexibleAggregationStrategy<LocalDateTime>()
                .storeInBody()
                .accumulateInCollection(TreeSet.class)
                .pick(body()))
                .streaming()
                .filter(header(Exchange.FILE_NAME).not().endsWith(".xml"))
                .log(LoggingLevel.INFO, correlation() + "Ignoring non-XML file ${header." + Exchange.FILE_NAME + "}")
                .stop()
                .end()
                .to("direct:parseCreatedAttribute")
                .end()
                .setHeader(DATASET_CREATION_TIME, simple("${body.last}"))
                .log(LoggingLevel.INFO, correlation() + "The dataset was created on ${header." + DATASET_CREATION_TIME + "}")
                .routeId("retrieve-dataset-creation-time");

        from("direct:parseCreatedAttribute")
                .setBody(xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/@created", String.class, XML_NAMESPACE_NETEX))
                .choice()
                .when(PredicateBuilder.or(body().isNull(), body().isEqualTo("")))
                .log(LoggingLevel.WARN, correlation() + "'created' attribute not found in file ${header." + Exchange.FILE_NAME + "}")
                .stop()
                .end()
                .bean(LocalDateTime.class, "parse(${body})")
                .routeId("parse-created-attribute");

        // Use an idempotent repository backed by a Kafka topic to identify duplicate import events.
        // a dataset import is uniquely identified by the concatenation of its codespace and creation date.
        from("direct:notifyConsumersIfNew")
                .idempotentConsumer(header(DATASET_IMPORT_KEY)).messageIdRepositoryRef("netexImportEventIdempotentRepo").skipDuplicate(false)
                .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(true))
                .log(LoggingLevel.INFO, correlation() + "An event has already been sent for this dataset. Skipping")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "This is a new dataset. Notifying consumers")
                .to("direct:notifyConsumers")
                .to("direct:publishServiceJourneys")
                .log(LoggingLevel.INFO, correlation() + "Dataset processing complete")
                .end()
                .routeId("notify-consumers-if-new");

        from("direct:notifyConsumers")
                .log(LoggingLevel.INFO, correlation() + "Notifying Kafka topic ${properties:nisaba.kafka.topic.event}")
                .setHeader(KafkaConstants.KEY, header(DATASET_CODESPACE))
                .to("kafka:{{nisaba.kafka.topic.event}}?clientId=nisaba-event&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&valueSerializer=io.confluent.kafka.serializers.KafkaAvroSerializer")
                .removeHeader(KafkaConstants.KEY)
                .routeId("notify-consumers");

        from("direct:publishServiceJourneys")
                .log(LoggingLevel.INFO, correlation() + "Publishing ServiceJourneys")
                .setBody(header(DATASET_CODESPACE))
                .to("direct:downloadNetexDataset")
                .split(new ZipSplitter())
                .streaming()
                .filter(header(Exchange.FILE_NAME).not().endsWith(".xml"))
                .log(LoggingLevel.INFO, correlation() + "Ignoring non-XML file ${header." + Exchange.FILE_NAME + "}")
                .stop()
                .end()
                .choice()
                .when(header(Exchange.FILE_NAME).startsWith("_"))
                .to("direct:processCommonFile")
                .otherwise()
                .to("direct:processLineFile")
                .routeId("publish-service-journeys");

        from("direct:processCommonFile")
                .log(LoggingLevel.INFO, correlation() + "Processing common file ${header." + Exchange.FILE_NAME + "}")
                .to("xslt-saxon:filterServiceLinks.xsl")
                .marshal().zipFile()
                .to("kafka:{{nisaba.kafka.topic.common}}?clientId=nisaba-common&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy")
                .routeId("process-common-file");

        from("direct:processLineFile")
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + Exchange.FILE_NAME + "}")
                .convertBodyTo(javax.xml.transform.dom.DOMSource.class)
                .setHeader(LINE_FILE, body())
                .split(xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney/@id", XML_NAMESPACE_NETEX))
                .parallelProcessing()
                .to("direct:processServiceJourney")
                .routeId("process-line-file");

        from("direct:processServiceJourney")
                .setBody(simple("${body.value}"))
                .log(LoggingLevel.INFO, correlation() + "Processing ServiceJourney ${body}")
                .setHeader(SERVICE_JOURNEY_ID, body())
                .setBody(header(LINE_FILE))
                .to("xslt-saxon:filterServiceJourney.xsl")
                .marshal().zipFile()
                .to("kafka:{{nisaba.kafka.topic.servicejourney}}?clientId=nisaba-servicejourney&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy")
                .routeId("process-service-journey");
    }

}
