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
import no.entur.nisaba.services.NisabaBlobStoreService;
import org.apache.camel.LoggingLevel;
import org.springframework.stereotype.Component;

import static no.entur.nisaba.Constants.FILE_HANDLE;


@Component
public class NisabaBlobStoreRoute extends BaseRouteBuilder {

    private final NisabaBlobStoreService nisabaBlobStoreService;

    public NisabaBlobStoreRoute(NisabaBlobStoreService nisabaBlobStoreService) {
        this.nisabaBlobStoreService = nisabaBlobStoreService;
    }

    @Override
    public void configure() {

        from("direct:uploadNisabaBlob")
                .to(logDebugShowAll())
                .bean(nisabaBlobStoreService, "uploadBlob")
                .setBody(simple(""))
                .to(logDebugShowAll())
                .log(LoggingLevel.INFO, correlation() + "Stored file ${header." + FILE_HANDLE + "} in blob store.")
                .routeId("blobstore-nisaba-upload");

        from("direct:getNisabaBlob")
                .to(logDebugShowAll())
                .bean(nisabaBlobStoreService, "getBlob")
                .to(logDebugShowAll())
                .log(LoggingLevel.INFO, correlation() + "Returning from fetching file ${header." + FILE_HANDLE + "} from Nisaba blob store.")
                .routeId("blobstore-nisaba-download");
    }
}
