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

public final class Constants {
    public static final String FILE_HANDLE = "RutebankenFileHandle";
    public static final String FILE_PREFIX = "RutebankenFilePrefix";
    public static final String TARGET_FILE_HANDLE = "RutebankenTargetFileHandle";
    public static final String TARGET_FILE_PARENT = "RutebankenTargetFileParent";
    public static final String TARGET_CONTAINER = "RutebankenTargetContainer";


    public static final String FILE_PARENT_COLLECTION = "RutebankenFileParentCollection";
    public static final String CORRELATION_ID = "RutebankenCorrelationId";

    public static final String CURRENT_AGGREGATED_NETEX_FILENAME = "aggregated-netex.zip";

    public static final String BLOBSTORE_PATH_OUTBOUND = "outbound/";


    public static final String BLOBSTORE_MAKE_BLOB_PUBLIC = "RutebankenBlobstoreMakeBlobPublic";

    public static final String SINGLETON_ROUTE_DEFINITION_GROUP_NAME = "RutebankenSingletonRouteDefinitionGroup";


    public static final String CAMEL_ALL_HEADERS = "Camel*";
    public static final String CAMEL_ALL_HTTP_HEADERS = "CamelHttp*";

    public static final String DATASET_IMPORT_KEY ="EnturDatasetImportKey";

    public static final String DATASET_CREATION_TIME = "EnturDatasetCreationTime";
    public static final String DATASET_CODESPACE = "EnturDatasetCodespace";
    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    private Constants() {
    }

}

