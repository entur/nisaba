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
import no.entur.nisaba.event.NetexImportEventKeyFactory;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.FlexibleAggregationStrategy;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.dataformat.zipfile.ZipSplitter;
import org.apache.camel.support.builder.PredicateBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.TreeSet;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.DATASET_ALL_CREATION_TIMES;
import static no.entur.nisaba.Constants.DATASET_CHOUETTE_IMPORT_KEY;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.DATASET_CONTENT;
import static no.entur.nisaba.Constants.DATASET_IMPORT_KEY;
import static no.entur.nisaba.Constants.DATASET_LATEST_CREATION_TIME;
import static no.entur.nisaba.Constants.DATASET_PUBLISHED_FILE_NAME;
import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.PUBLICATION_DELIVERY_TIMESTAMP;
import static no.entur.nisaba.Constants.XML_NAMESPACE_NETEX;
import static org.apache.camel.builder.Builder.bean;

/**
 * Receive a notification when a new NeTEx export is available in the blob store and send an event in a Kafka topic
 * if this is the first time the dataset is published.
 */
@Component
public class NetexImportNotificationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String EXPORT_FILE_NAME = "netex/rb_${body}-" + Constants.CURRENT_AGGREGATED_NETEX_FILENAME;

    @Override
    public void configure() throws Exception {
        super.configure();


        from("master:lockOnNetexExportNotificationQueueRoute:google-pubsub:{{nisaba.pubsub.project.id}}:NetexExportNotificationQueue")

                .process(this::setCorrelationIdIfMissing)
                .setHeader(DATASET_CODESPACE, bodyAs(String.class))
                .log(LoggingLevel.INFO, correlation() + "Received NeTEx export notification")
                .setHeader(DATASET_PUBLISHED_FILE_NAME, simple(BLOBSTORE_PATH_OUTBOUND + EXPORT_FILE_NAME))
                .to("direct:downloadNetexDataset")
                .filter(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "NeTEx export file not found")
                .stop()
                //end filter
                .end()
                .setHeader(DATASET_CONTENT, body())
                .log(LoggingLevel.INFO, correlation() + "NeTEx export file downloaded")
                .to("direct:retrieveDatasetCreationTime")
                .setHeader(DATASET_IMPORT_KEY, bean(NetexImportEventKeyFactory.class, "createNetexImportEventKey(${header." + DATASET_CODESPACE + "}, ${header." + DATASET_LATEST_CREATION_TIME + "})"))
                .to("direct:notifyConsumersIfNew")
                .routeId("netex-export-notification-queue");

        from("direct:downloadNetexDataset")
                .streamCaching()
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx dataset")
                .setHeader(FILE_HANDLE, header(DATASET_PUBLISHED_FILE_NAME))
                .to("direct:getMardukBlob")
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
                .setHeader(DATASET_ALL_CREATION_TIMES, body())
                .setHeader(DATASET_LATEST_CREATION_TIME, simple("${body.last}"))
                .log(LoggingLevel.INFO, correlation() + "The dataset was created on ${header." + DATASET_LATEST_CREATION_TIME + "}")
                .routeId("retrieve-dataset-creation-time");

        from("direct:parseCreatedAttribute")
                .setBody(xpath("/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/@created", String.class, XML_NAMESPACE_NETEX))
                .filter(PredicateBuilder.or(body().isNull(), body().isEqualTo("")))
                .log(LoggingLevel.WARN, correlation() + "'created' attribute not found in file ${header." + Exchange.FILE_NAME + "}")
                .stop()
                //end filter
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
                .setHeader(PUBLICATION_DELIVERY_TIMESTAMP, LocalDateTime::now)
                .to("direct:publishDataset")
                .to("direct:notifyConsumers")
                .log(LoggingLevel.INFO, correlation() + "Dataset processing complete")
                .end()
                .routeId("notify-consumers-if-new");


        from("direct:notifyConsumers")
                .log(LoggingLevel.INFO, correlation() + "Notifying Kafka topic ${properties:nisaba.kafka.topic.event}")
                .to("direct:findChouetteImportKey")
                .bean("NetexImportEventFactory", "createNetexImportEvent")
                .setHeader(KafkaConstants.KEY, header(DATASET_CODESPACE))
                .to("kafka:{{nisaba.kafka.topic.event}}?clientId=nisaba-event&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&valueSerializer=io.confluent.kafka.serializers.KafkaAvroSerializer").id("to-kafka-topic-event")
                .removeHeader(KafkaConstants.KEY)
                .log(LoggingLevel.INFO, correlation() + "Notified export of ${body.serviceJourneys} service journeys and ${body.commonFiles} common files")
                .routeId("notify-consumers");

        // A published dataset that contains flexible lines is made of two source datasets,
        // one created by chouette and one created by Uttu.
        // The dataset that should be referenced as the original dataset in the Kafka event is the one created by chouette.
        // To identify it, we look up in the exchange bucket for a file whose name matches any of the creation dates found in the CompositeFrames.
        // only the file that corresponds to the dataset imported by chouette exists in that bucket.
        from("direct:findChouetteImportKey")
                .split(header(DATASET_ALL_CREATION_TIMES)).aggregationStrategy(new FlexibleAggregationStrategy<String>()
                .storeInHeader(DATASET_CHOUETTE_IMPORT_KEY)
                .pick(header(DATASET_CHOUETTE_IMPORT_KEY)))
                .bean(NetexImportEventKeyFactory.class, "createNetexImportEventKey(${header." + DATASET_CODESPACE + "}, ${body})")
                .setHeader(DATASET_CHOUETTE_IMPORT_KEY, body())
                .setHeader(FILE_HANDLE, simple("imported/${header." + DATASET_CODESPACE + "}/${body}.zip"))
                .to("direct:getNisabaExchangeBlob")
                .filter(body().isNull())
                .removeHeader(DATASET_CHOUETTE_IMPORT_KEY)
                //end filter
                .end()
                //end split
                .end()
                .filter(header(DATASET_CHOUETTE_IMPORT_KEY).isNull())
                .log(LoggingLevel.WARN, correlation() + "Chouette import key not found")
                .routeId("find-chouette-import-key");

    }

}
