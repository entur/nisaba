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

import org.apache.camel.support.builder.Namespaces;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public final class Constants {
    public static final String FILE_HANDLE = "RutebankenFileHandle";
    public static final String CORRELATION_ID = "RutebankenCorrelationId";

    public static final String TARGET_FILE_HANDLE = "RutebankenTargetFileHandle";
    public static final String TARGET_CONTAINER = "RutebankenTargetContainer";

    public static final String CURRENT_AGGREGATED_NETEX_FILENAME = "aggregated-netex.zip";

    public static final String BLOBSTORE_PATH_OUTBOUND = "outbound/";

    public static final String DATASET_IMPORT_KEY = "EnturDatasetImportKey";

    public static final String DATASET_CONTENT = "EnturDatasetContent";
    public static final String DATASET_CHOUETTE_IMPORT_KEY = "EnturDatasetChouetteImportKey";
    public static final String DATASET_ALL_CREATION_TIMES = "EnturDatasetAllCreationTimes";
    public static final String DATASET_LATEST_CREATION_TIME = "EnturDatasetLatestCreationTime";
    public static final String DATASET_CODESPACE = "EnturDatasetCodespace";
    public static final String DATASET_PUBLISHED_FILE_NAME = "EnturDatasetPublishedFileName";

    public static final String DATASET_STAT = "DATASET_STAT";

    public static final String NETEX_FILE_CONTENT = "NETEX_FILE_CONTENT";
    public static final String NETEX_FILE_NAME = "NETEX_FILE_NAME";

    public static final String GCS_BUCKET_FILE_NAME = "${header." + DATASET_IMPORT_KEY + "}/${header." + NETEX_FILE_NAME + "}.zip";

    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true).optionalEnd()
            .optionalStart().appendPattern("XXXXX")
            .optionalEnd().toFormatter();

    public static final Namespaces XML_NAMESPACE_NETEX = new Namespaces("netex", "http://www.netex.org.uk/netex");

    public static final String SERVICE_JOURNEY_ID = "SERVICE_JOURNEY_ID";
    public static final String COMMON_FILE_INDEX = "COMMON_FILE_INDEX";
    public static final String LINE_FILE_INDEX = "LINE_FILE_INDEX";


    public static final String LINE_REFERENCES = "LINE_REFERENCES";
    public static final String ROUTE_REFERENCES = "ROUTE_REFERENCES";
    public static final String JOURNEY_PATTERN_REFERENCES = "JOURNEY_PATTERN_REFERENCES";

    public static final String PUBLICATION_DELIVERY_TIMESTAMP = "PUBLICATION_DELIVERY_TIMESTAMP";

    private Constants() {
    }

}

