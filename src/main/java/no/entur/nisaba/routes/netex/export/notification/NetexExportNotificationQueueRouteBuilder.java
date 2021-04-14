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

package no.entur.nisaba.routes.netex.export.notification;

import no.entur.nisaba.Constants;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.FlexibleAggregationStrategy;
import org.apache.camel.dataformat.zipfile.ZipSplitter;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.TreeSet;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.CHOUETTE_REFERENTIAL;
import static no.entur.nisaba.Constants.DATASET_NEW_CREATION_TIMESTAMP;
import static no.entur.nisaba.Constants.DATASET_OLD_CREATION_TIMESTAMP;
import static no.entur.nisaba.Constants.FILE_HANDLE;

/**
 * Receive a notification when a new NeTEx export is available in the blob store.
 */
@Component
public class NetexExportNotificationQueueRouteBuilder extends BaseRouteBuilder {

    private static final String EXPORT_FILE_NAME = "netex/${body}-" + Constants.CURRENT_AGGREGATED_NETEX_FILENAME;
    private static final String CREATED_ATTRIBUTE = "EnturCreatedAttribute";


    @Override
    public void configure() throws Exception {
        super.configure();


        singletonFrom("entur-google-pubsub:NetexExportNotificationQueue")
                .setHeader(CHOUETTE_REFERENTIAL, body())
                .log(LoggingLevel.INFO, correlation() + "Received export notification")
                .to("direct:downloadNetexExport")
                .choice()
                .when(body().isNull())
                .log(LoggingLevel.ERROR, correlation() + "NeTex export file not found")
                .stop()
                .end()
                .log(LoggingLevel.INFO, correlation() + "NeTex export file found")
                .to("direct:retrieveNewCreationTimestamp")
                .to("direct:retrieveOldCreationTimestamp")
                .log(LoggingLevel.INFO, correlation() + "The new creation timestamp is ${header." + DATASET_NEW_CREATION_TIMESTAMP + "} and the old one is ${header." + DATASET_OLD_CREATION_TIMESTAMP + "}")
                .choice()
                .when(header(DATASET_NEW_CREATION_TIMESTAMP).isGreaterThan(header(DATASET_OLD_CREATION_TIMESTAMP)))
                .log(LoggingLevel.INFO, correlation() + "This dataset has not been exported before. Notifying consumers.")
                .to("direct:notifyConsumers")
                .otherwise()
                .log(LoggingLevel.INFO, correlation() + "This dataset has already been exported. Ignoring.")
                .end()
                .routeId("netex-export-notification-queue");

        from("direct:downloadNetexExport")
                .log(LoggingLevel.INFO, correlation() + "Downloading NeTEx export")
                .setHeader(FILE_HANDLE, simple(BLOBSTORE_PATH_OUTBOUND + EXPORT_FILE_NAME))
                .to("direct:getBlob")
                .routeId("download-netex-export");

        from("direct:retrieveOldCreationTimestamp")
                .log(LoggingLevel.INFO, correlation() + "Retrieve previously notified creation timestamp")
                .setHeader(DATASET_OLD_CREATION_TIMESTAMP, () -> LocalDateTime.now().minusDays(10))
                .routeId("retrieve-old-creation-timestamp");

        from("direct:retrieveNewCreationTimestamp")
                .log(LoggingLevel.INFO, correlation() + "Retrieving creation timestamp")
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
                .setProperty(CREATED_ATTRIBUTE, simple("${body.createdTimestamp}"))
                .end()
                .setHeader(DATASET_NEW_CREATION_TIMESTAMP, simple("${body.last}"))
                .routeId("retrieve-new-creation-timestamp");


        from("direct:notifyConsumers")
                .log(LoggingLevel.INFO, correlation() + "Notifying consumers")
                .routeId("notify-consumers");


    }

}
