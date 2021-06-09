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

package no.entur.nisaba;

import org.apache.camel.Exchange;
import org.apache.camel.support.builder.Namespaces;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public final class Constants {
    public static final String FILE_HANDLE = "RutebankenFileHandle";
    public static final String CORRELATION_ID = "RutebankenCorrelationId";
    public static final String CURRENT_AGGREGATED_NETEX_FILENAME = "aggregated-netex.zip";

    public static final String BLOBSTORE_PATH_OUTBOUND = "outbound/";

    public static final String DATASET_IMPORT_KEY = "EnturDatasetImportKey";
    public static final String GCS_BUCKET_FILE_NAME = "${header." + DATASET_IMPORT_KEY + "}/${header." + Exchange.FILE_NAME + "}";
    public static final String DATASET_CHOUETTE_IMPORT_KEY = "EnturDatasetChouetteImportKey";
    public static final String DATASET_ALL_CREATION_TIMES = "EnturDatasetAllCreationTimes";
    public static final String DATASET_CREATION_TIME = "EnturDatasetCreationTime";
    public static final String DATASET_CODESPACE = "EnturDatasetCodespace";
    public static final String DATASET_PUBLISHED_FILE_NAME = "EnturDatasetPublishedFileName";

    public static final String DATASET_STAT = "DATASET_STAT";

     public static final DateTimeFormatter DATE_TIME_FORMATTER =  new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true).optionalEnd()
            .optionalStart().appendPattern("XXXXX")
            .optionalEnd().toFormatter();

    public static final Namespaces XML_NAMESPACE_NETEX = new Namespaces("netex", "http://www.netex.org.uk/netex");
    public static final String PUBLICATION_DELIVERY_TEMPLATE = "PUBLICATION_DELIVERY_TEMPLATE";

    private Constants() {
    }

}

