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

package no.entur.nisaba.idempotent;

import com.google.cloud.storage.Storage;
import org.rutebanken.helper.gcp.BlobStoreHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(IdempotentRepositoryProperties.class)
public class IdempotentRepositoryConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentRepositoryConfig.class);

    @Bean
    @Profile("gcs-blobstore")
    Storage idempotentRepositoryStorage(@Value("${blobstore.gcs.project.id}") String projectId,
                         @Value("${blobstore.gcs.credential.path:#{null}}") String credentialPath) {
        return credentialPath == null || credentialPath.isBlank()
                ? BlobStoreHelper.getStorage(projectId)
                : BlobStoreHelper.getStorage(credentialPath, projectId);
    }

    /**
     * Fails the start-up when the bucket is missing or not accessible.
     */
    @Bean
    @Profile("gcs-blobstore")
    MarkerStore gcsMarkerStore(Storage idempotentRepositoryStorage, IdempotentRepositoryProperties properties) {
        GcsMarkerStore store = new GcsMarkerStore(idempotentRepositoryStorage, properties.gcs().bucket());
        store.checkAccess();
        LOGGER.info("The idempotent repository is stored in gs://{}", properties.gcs().bucket());
        return store;
    }

    @Bean
    @Profile("!gcs-blobstore")
    InMemoryMarkerStore inMemoryMarkerStore() {
        return new InMemoryMarkerStore();
    }

    @Bean
    GcsIdempotentRepository idempotentRepository(MarkerStore markerStore, IdempotentRepositoryProperties properties) {
        return new GcsIdempotentRepository(markerStore, instanceId(), properties.staleAfter(), Clock.systemUTC());
    }

    /**
     * The pod name, so that a claim id says which pod wrote it.
     */
    static String instanceId() {
        String hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank() ? UUID.randomUUID().toString() : hostname;
    }
}
