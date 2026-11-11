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

package no.entur.nisaba.event;

import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.pipeline.ChouetteImportKeyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.LocalDateTime;

import static no.entur.nisaba.Constants.DATE_TIME_FORMATTER;

/**
 * Create the Avro NeTEx import event.
 */
@Component
public class NetexImportEventFactory {

    private final String mardukBucketName;

    public NetexImportEventFactory(@Value("${blobstore.gcs.marduk.container.name}") String mardukBucketName) {
        this.mardukBucketName = mardukBucketName;
    }


    /**
     * @param chouetteImportKey the import key of the dataset Chouette imported, or null when none was found.
     */
    public NetexImportEvent createNetexImportEvent(String codespace,
                                                   LocalDateTime creationDate,
                                                   String importKey,
                                                   String chouetteImportKey,
                                                   String publishedFileName) {
        Assert.notNull(codespace, "codespace was null");
        Assert.notNull(creationDate, "creationDate was null");
        Assert.notNull(importKey, "importKey was null");
        Assert.notNull(publishedFileName, "publishedFileName was null");

        String originalDatasetURI = "";
        if (chouetteImportKey != null) {
            originalDatasetURI = ChouetteImportKeyResolver.importedFileName(codespace, chouetteImportKey);
        }
        return NetexImportEvent.newBuilder()
                .setCodespace(codespace)
                .setImportDateTime(DATE_TIME_FORMATTER.format(creationDate))
                .setImportKey(importKey)
                .setPublishedDatasetURI("gs://" + mardukBucketName + "/" + publishedFileName)
                .setPublishedDatasetPublicLink("https://storage.googleapis.com/" + mardukBucketName + "/" + publishedFileName)
                .setOriginalDatasetURI(originalDatasetURI)
                .setServiceJourneys(0)
                .setCommonFiles(0)
                .build();
    }
}
