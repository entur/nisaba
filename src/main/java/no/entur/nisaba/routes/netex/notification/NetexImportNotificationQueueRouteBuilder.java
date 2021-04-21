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
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.FlexibleAggregationStrategy;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.dataformat.zipfile.ZipSplitter;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.TreeSet;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.DATASET_CREATION_TIME;
import static no.entur.nisaba.Constants.FILE_HANDLE;

/**
 * Receive a notification when a new NeTEx export is available in the blob store and send an event in a Kafka topic
 * if this is the first time the dataset is published.
 */
@Component
public class NetexImportNotificationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String EXPORT_FILE_NAME = "netex/rb_${body}-" + Constants.CURRENT_AGGREGATED_NETEX_FILENAME;
    private static final String CREATED_ATTRIBUTE = "EnturCreatedAttribute";

    @Override
    public void configure() throws Exception {
        super.configure();


        singletonFrom("entur-google-pubsub:NetexExportNotificationQueue")
                .process(this::setCorrelationIdIfMissing)
                .setHeader(DATASET_CODESPACE, body())
                .log(LoggingLevel.INFO, correlation() + "Received NeTEx export notification")
                .to("direct:downloadNetexDataset")
                .choice()
                .when(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "NeTEx export file not found")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "NeTEx export file downloaded")
                .to("direct:retrieveDatasetCreationTime")
                .process(new NetexImportEventProcessor())
                .setHeader(KafkaConstants.KEY, simple("${body.key}"))
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
                .pick(exchangeProperty(CREATED_ATTRIBUTE)))
                .streaming()
                .filter(header(Exchange.FILE_NAME).not().endsWith(".xml"))
                .log(LoggingLevel.INFO, correlation() + "Ignoring non-XML file ${header." + Exchange.FILE_NAME + "}")
                .stop()
                .end()
                .to("stax:no.entur.nisaba.stax.CreatedAttributeInCompositeFrameHandler")
                .setProperty(CREATED_ATTRIBUTE, simple("${body.createdTime}"))
                .end()
                .setHeader(DATASET_CREATION_TIME, simple("${body.last}"))
                .log(LoggingLevel.INFO, correlation() + "The dataset was created on ${header." + DATASET_CREATION_TIME + "}")
                .routeId("retrieve-dataset-creation-time");

        // Use an idempotent repository backed by a Kafka topic to identify duplicate import events.
        // a dataset import is uniquely identified by the concatenation of its codespace and creation date.
        from("direct:notifyConsumersIfNew")
                .idempotentConsumer(header(KafkaConstants.KEY)).messageIdRepositoryRef("netexImportEventIdempotentRepo").skipDuplicate(false)
                .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(true))
                .log(LoggingLevel.INFO, correlation() + "An event has already been sent for this dataset. Skipping")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "This is a new dataset. Notifying consumers")
                .to("direct:notifyConsumers")
                .end()
                .routeId("notify-consumers-if-new");

        from("direct:notifyConsumers")
                .log(LoggingLevel.INFO, correlation() + "Notifying Kafka topic ${properties:nisaba.kafka.topic.event}")
                .marshal().json(JsonLibrary.Jackson)
                .to("kafka:{{nisaba.kafka.topic.event}}?headerFilterStrategy=#kafkaFilterAllHeadersFilterStrategy")
                .routeId("notify-consumers");


    }

}
