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

import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.event.NetexImportEventFactory;
import no.entur.nisaba.event.NetexImportEventKeyFactory;
import no.entur.nisaba.idempotent.IdempotentRepository;
import no.entur.nisaba.idempotent.IdempotentRepository.Claim;
import no.entur.nisaba.kafka.NetexImportEventPublisher;
import no.entur.nisaba.pubsub.NetexExportNotification;
import no.entur.nisaba.services.MardukBlobStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Optional;

import static no.entur.nisaba.Constants.BLOBSTORE_PATH_OUTBOUND;
import static no.entur.nisaba.Constants.CURRENT_AGGREGATED_NETEX_FILENAME;

/**
 * What happens to one NeTEx export notification: download the published dataset, find its creation time,
 * claim its import key, and publish the import event if the key is new.
 * <p>
 * This is the Camel route {@code netex-export-notification-queue} and the direct routes it called, as one
 * method. The log lines and their {@code [codespace=... correlationId=...] } prefix are kept verbatim.
 */
@Component
public class NetexImportPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetexImportPipeline.class);

    private final MardukBlobStoreService mardukBlobStoreService;
    private final DatasetCreationTimeExtractor creationTimeExtractor;
    private final ChouetteImportKeyResolver chouetteImportKeyResolver;
    private final NetexImportEventFactory eventFactory;
    private final IdempotentRepository idempotentRepository;
    private final NetexImportEventPublisher publisher;
    private final NetexImportMetrics metrics;
    private final String eventTopic;

    public NetexImportPipeline(MardukBlobStoreService mardukBlobStoreService,
                               DatasetCreationTimeExtractor creationTimeExtractor,
                               ChouetteImportKeyResolver chouetteImportKeyResolver,
                               NetexImportEventFactory eventFactory,
                               IdempotentRepository idempotentRepository,
                               NetexImportEventPublisher publisher,
                               NetexImportMetrics metrics,
                               @Value("${nisaba.kafka.topic.event}") String eventTopic) {
        this.mardukBlobStoreService = mardukBlobStoreService;
        this.creationTimeExtractor = creationTimeExtractor;
        this.chouetteImportKeyResolver = chouetteImportKeyResolver;
        this.eventFactory = eventFactory;
        this.idempotentRepository = idempotentRepository;
        this.publisher = publisher;
        this.metrics = metrics;
        this.eventTopic = eventTopic;
    }

    public static String publishedFileName(String codespace) {
        return BLOBSTORE_PATH_OUTBOUND + "netex/rb_" + codespace + "-" + CURRENT_AGGREGATED_NETEX_FILENAME;
    }

    public void process(NetexExportNotification notification) {
        String prefix = ImportMdc.prefix();
        String codespace = notification.codespace();
        LOGGER.info("{}Received NeTEx export notification", prefix);

        String publishedFileName = publishedFileName(codespace);
        LOGGER.info("{}Downloading NeTEx dataset", prefix);
        InputStream dataset = mardukBlobStoreService.getBlob(publishedFileName);
        if (dataset == null) {
            LOGGER.error("{}NeTEx export file not found", prefix);
            metrics.count(NetexImportMetrics.Outcome.DATASET_MISSING);
            return;
        }
        LOGGER.info("{}NeTEx export file downloaded", prefix);

        LOGGER.info("{}Retrieving dataset creation time", prefix);
        DatasetCreationTimes creationTimes = creationTimeExtractor.extract(dataset);
        LocalDateTime latest = creationTimes.latest();
        LOGGER.info("{}The dataset was created on {}", prefix, latest);
        String importKey = NetexImportEventKeyFactory.createNetexImportEventKey(codespace, latest);

        Optional<Claim> claim = idempotentRepository.claim(importKey);
        if (claim.isEmpty()) {
            LOGGER.info("{}An event has already been sent for this dataset. Skipping", prefix);
            metrics.count(NetexImportMetrics.Outcome.DUPLICATE);
            return;
        }
        LOGGER.info("{}This is a new dataset. Notifying consumers", prefix);
        try {
            LOGGER.info("{}Notifying Kafka topic {}", prefix, eventTopic);
            Optional<String> chouetteImportKey = chouetteImportKeyResolver.resolve(codespace, creationTimes.all());
            NetexImportEvent event = eventFactory.createNetexImportEvent(codespace, latest, importKey,
                    chouetteImportKey.orElse(null), publishedFileName);
            publisher.publish(event);
            idempotentRepository.confirm(claim.get());
            LOGGER.info("{}Notified Kafka topic {} of new dataset {}", prefix, eventTopic, event.getImportKey());
        } catch (RuntimeException | Error e) {
            // mirrors Camel's removeOnFailure: a redelivery can claim the key again
            idempotentRepository.release(claim.get());
            throw e;
        }
        LOGGER.info("{}Dataset processing complete", prefix);
        metrics.count(NetexImportMetrics.Outcome.PUBLISHED);
    }
}
