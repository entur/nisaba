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

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public final class Constants {

    /**
     * PubSub attribute carrying Marduk's correlation id (Marduk sends none today).
     */
    public static final String CORRELATION_ID = "RutebankenCorrelationId";

    public static final String CURRENT_AGGREGATED_NETEX_FILENAME = "aggregated-netex.zip";

    public static final String BLOBSTORE_PATH_OUTBOUND = "outbound/";
    public static final String BLOBSTORE_PATH_IMPORTED = "imported/";

    /**
     * The one record header on the Kafka import event: the import key.
     */
    public static final String DATASET_IMPORT_KEY = "EnturDatasetImportKey";

    public static final String NETEX_NAMESPACE = "http://www.netex.org.uk/netex";

    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true).optionalEnd()
            .optionalStart().appendPattern("XXXXX")
            .optionalEnd().toFormatter();

    private Constants() {
    }

}
