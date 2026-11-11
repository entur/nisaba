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

package no.entur.nisaba.pipeline;

import no.entur.nisaba.event.NetexImportEventKeyFactory;
import no.entur.nisaba.services.NisabaExchangeBlobStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.SortedSet;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_IMPORTED;

/**
 * Finds the import key of the dataset Chouette imported.
 * <p>
 * A published dataset that contains flexible lines is made of two source datasets, one created by Chouette and
 * one created by Uttu, and carries both creation times. The one to reference as the original dataset is
 * Chouette's: only its archive exists in the exchange bucket, under {@code imported/<codespace>/<key>.zip}.
 * When several exist, the most recent wins, as the Camel aggregation did.
 */
@Component
public class ChouetteImportKeyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChouetteImportKeyResolver.class);

    private final NisabaExchangeBlobStoreService exchangeBlobStoreService;
    private final String exchangeBucketName;

    public ChouetteImportKeyResolver(NisabaExchangeBlobStoreService exchangeBlobStoreService,
                                     @Value("${blobstore.gcs.nisaba.exchange.container.name}") String exchangeBucketName) {
        this.exchangeBlobStoreService = exchangeBlobStoreService;
        this.exchangeBucketName = exchangeBucketName;
    }

    /**
     * Where Marduk stores the archive of an import in the exchange bucket.
     */
    public static String importedFileName(String codespace, String importKey) {
        return BLOBSTORE_PATH_IMPORTED + codespace + "/" + importKey + ".zip";
    }

    public Optional<String> resolve(String codespace, SortedSet<LocalDateTime> creationTimes) {
        String found = null;
        for (LocalDateTime creationTime : creationTimes) {
            String importKey = NetexImportEventKeyFactory.createNetexImportEventKey(codespace, creationTime);
            String path = importedFileName(codespace, importKey);
            if (exchangeBlobStoreService.exist(path)) {
                found = importKey;
            } else {
                LOGGER.info("File '{}' in bucket '{}' does not exist", path, exchangeBucketName);
            }
        }
        if (found == null) {
            LOGGER.warn("{}Chouette import key not found", ImportMdc.prefix());
        }
        return Optional.ofNullable(found);
    }
}
