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

package no.entur.nisaba.routes.blobstore;

import no.entur.nisaba.routes.BaseRouteBuilder;
import no.entur.nisaba.services.NisabaExchangeBlobStoreService;
import org.apache.camel.LoggingLevel;
import org.springframework.stereotype.Component;

import static no.entur.nisaba.Constants.FILE_HANDLE;
import static no.entur.nisaba.Constants.TARGET_CONTAINER;
import static no.entur.nisaba.Constants.TARGET_FILE_HANDLE;


@Component
public class NisabaExchangeBlobStoreRoute extends BaseRouteBuilder {

    private final NisabaExchangeBlobStoreService nisabaExchangeBlobStoreService;

    public NisabaExchangeBlobStoreRoute(NisabaExchangeBlobStoreService nisabaExchangeBlobStoreService) {
        this.nisabaExchangeBlobStoreService = nisabaExchangeBlobStoreService;
    }

    @Override
    public void configure() {

        from("direct:getNisabaExchangeBlob")
                .to(logDebugShowAll())
                .bean(nisabaExchangeBlobStoreService, "getBlob")
                .to(logDebugShowAll())
                .log(LoggingLevel.INFO, correlation() + "Returning from fetching file ${header." + FILE_HANDLE + "} from Nisaba Exchange bucket.")
                .routeId("blobstore-nisaba-exchange-download");

        from("direct:copyBlobToAnotherBucket")
                .to(logDebugShowAll())
                .bean(nisabaExchangeBlobStoreService, "copyBlobToAnotherBucket")
                .to(logDebugShowAll())
                .log(LoggingLevel.INFO, correlation() + "Copied file ${header." + FILE_HANDLE + "} to file ${header." + TARGET_FILE_HANDLE + "} from Marduk bucket to bucket ${header." + TARGET_CONTAINER + "}.")
                .routeId("blobstore-copy-to-another-bucket");
    }


}
