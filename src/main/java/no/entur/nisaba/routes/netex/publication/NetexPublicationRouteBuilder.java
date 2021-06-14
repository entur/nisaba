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
import no.entur.nisaba.event.DatasetStatHelper;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.FlexibleAggregationStrategy;
import org.apache.camel.dataformat.zipfile.ZipSplitter;
import org.springframework.stereotype.Component;

import java.util.HashSet;

import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.XML_NAMESPACE_NETEX;

/**
 * Receive a notification when a new NeTEx export is available in the blob store and send an event in a Kafka topic
 * if this is the first time the dataset is published.
 */
@Component
public class NetexPublicationRouteBuilder extends BaseRouteBuilder {

    private static final String NB_SERVICE_JOURNEYS_IN_FILE = "NB_SERVICE_JOURNEYS_IN_FILE";
    private static final String LINE_FILE_NAMES = "LINE_FILE_NAMES";

    @Override
    public void configure() throws Exception {
        super.configure();


        from("direct:publishDataset")
                .streamCaching()
                .log(LoggingLevel.INFO, correlation() + "Publishing Dataset")
                .bean(DatasetStatHelper.class, "init")
                .setBody(header(DATASET_CODESPACE))
                .to("direct:downloadNetexDataset")
                .to("direct:processFiles")
                .log(LoggingLevel.INFO, correlation() + "Processed ${header." + LINE_FILE_NAMES + ".size()}  files")
                .filter(simple("${properties:nisaba.publish.enabled:true}"))

                .split(header(LINE_FILE_NAMES))
                .convertBodyTo(String.class)
                .to("google-pubsub:{{nisaba.pubsub.project.id}}:NetexServiceJourneyPublicationQueue")
                // end split
                .end()
                // end filter
                .end()

                .routeId("publish-dataset");

        from("direct:processFiles")
                .streamCaching()
                .split(new ZipSplitter()).aggregationStrategy(new FlexibleAggregationStrategy<String>()
                .storeInHeader(LINE_FILE_NAMES)
                .accumulateInCollection(HashSet.class)
                .pick(body()))
                .streaming()
                .log(LoggingLevel.INFO, correlation() + "Processing file ${header." + Exchange.FILE_NAME + "}")

                .choice()
                .when(header(Exchange.FILE_NAME).not().endsWith(".xml"))
                .to("direct:processUnknownFile")
                .when(header(Exchange.FILE_NAME).startsWith("_"))
                .to("direct:processCommonFile")
                .otherwise()
                .to("direct:processLineFile")
                .end()
                .setBody(simple(Constants.GCS_BUCKET_FILE_NAME))
                .routeId("process-files");

        from("direct:processUnknownFile")
                .streamCaching()
                .log(LoggingLevel.INFO, correlation() + "Ignoring non-XML file ${header." + Exchange.FILE_NAME + "}")
                .setBody(simple("${null}"))
                .stop()
                .routeId("process-unknown-file");

        from("direct:processLineFile")
                .streamCaching()
                .log(LoggingLevel.INFO, correlation() + "Processing line file ${header." + Exchange.FILE_NAME + "}")
                .setHeader(NB_SERVICE_JOURNEYS_IN_FILE,
                        xpath("count(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney)", Integer.class, XML_NAMESPACE_NETEX))
                .bean(DatasetStatHelper.class, "addServiceJourneys(${header.NB_SERVICE_JOURNEYS_IN_FILE})")
                .marshal().zipFile()
                .to("direct:uploadNetexFile")
                .log(LoggingLevel.INFO, correlation() + "Processed line file ${header." + Exchange.FILE_NAME + "}")
                .routeId("process-line-file");

        from("direct:uploadNetexFile")
                .setHeader(FILE_HANDLE, simple(Constants.GCS_BUCKET_FILE_NAME))
                .log(LoggingLevel.INFO, correlation() + "Uploading NeTEx file ${header." + Exchange.FILE_NAME + "} to GCS file ${header." + FILE_HANDLE + "}")
                .to("direct:uploadNisabaBlob")
                .routeId("upload-netex-file");

    }

}
