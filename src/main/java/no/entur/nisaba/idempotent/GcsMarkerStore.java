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

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Markers as objects of a GCS bucket. The object name is the import key, the content and the
 * {@value #METADATA_CLAIM_ID} metadata carry the claim id, the {@value #METADATA_CONFIRMED} metadata is set
 * once the event is published. The create uses the {@code ifGenerationMatch=0} precondition, so the first
 * create of a key wins whatever the pod; the confirm and the delete are conditioned on the generation the
 * create returned.
 * <p>
 * The client library retries a failed create or delete on its own; a create whose success was lost on the way
 * back is retried and then reported as "already exists", which the repository tells apart by reading the claim
 * id back. It does not retry the confirm (a metadata update is retried only with a metageneration
 * precondition); the repository does.
 */
public final class GcsMarkerStore implements MarkerStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(GcsMarkerStore.class);

    static final String METADATA_CLAIM_ID = "claim-id";
    static final String METADATA_CONFIRMED = "confirmed";
    private static final String CONTENT_TYPE = "text/plain";
    private static final int PRECONDITION_FAILED = 412;
    private static final int NOT_FOUND = 404;

    /** Where the start-up access check writes its probe objects; an import key starts with a codespace. */
    static final String ACCESS_CHECK_PREFIX = ".access-check/";
    private static final String ACCESS_CHECK_CLAIM_ID = "access-check";

    private final Storage storage;
    private final String bucket;

    public GcsMarkerStore(Storage storage, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("The bucket of the idempotent repository is not set");
        }
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public OptionalLong createIfAbsent(String name, String claimId, boolean confirmed) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(METADATA_CLAIM_ID, claimId);
        if (confirmed) {
            metadata.put(METADATA_CONFIRMED, "true");
        }
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, name))
                .setContentType(CONTENT_TYPE)
                .setMetadata(metadata)
                .build();
        try {
            Blob blob = storage.create(info, claimId.getBytes(StandardCharsets.UTF_8), Storage.BlobTargetOption.doesNotExist());
            return OptionalLong.of(blob.getGeneration());
        } catch (StorageException e) {
            if (e.getCode() == PRECONDITION_FAILED) {
                return OptionalLong.empty();
            }
            throw new IdempotentRepositoryException("Could not create " + location(name), e);
        }
    }

    @Override
    public Optional<Marker> read(String name) {
        Blob blob;
        try {
            blob = storage.get(BlobId.of(bucket, name));
        } catch (StorageException e) {
            throw new IdempotentRepositoryException("Could not read " + location(name), e);
        }
        if (blob == null) {
            return Optional.empty();
        }
        Map<String, String> metadata = blob.getMetadata() == null ? Map.of() : blob.getMetadata();
        String claimId = metadata.get(METADATA_CLAIM_ID);
        OffsetDateTime created = blob.getCreateTimeOffsetDateTime();
        if (created == null) {
            // never the case with GCS; an object of unknown age is treated as fresh, never as stale
            LOGGER.warn("{} has no creation time", location(name));
        }
        return Optional.of(new Marker(blob.getGeneration(), claimId == null ? "" : claimId,
                "true".equals(metadata.get(METADATA_CONFIRMED)), created == null ? Instant.now() : created.toInstant()));
    }

    @Override
    public boolean confirm(String name, long generation) {
        // a patch: metadata keys not mentioned are kept, and the generation does not change
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, name, generation))
                .setMetadata(Map.of(METADATA_CONFIRMED, "true"))
                .build();
        try {
            storage.update(info, Storage.BlobTargetOption.generationMatch());
            return true;
        } catch (StorageException e) {
            if (e.getCode() == PRECONDITION_FAILED || e.getCode() == NOT_FOUND) {
                return false;
            }
            throw new IdempotentRepositoryException("Could not confirm " + location(name), e);
        }
    }

    @Override
    public boolean deleteIfGeneration(String name, long generation) {
        try {
            return storage.delete(BlobId.of(bucket, name), Storage.BlobSourceOption.generationMatch(generation));
        } catch (StorageException e) {
            if (e.getCode() == PRECONDITION_FAILED || e.getCode() == NOT_FOUND) {
                return false;
            }
            throw new IdempotentRepositoryException("Could not delete " + location(name), e);
        }
    }

    /**
     * Creates, confirms and deletes a probe object: the three writes of a claim. Listing the bucket is not
     * enough, a read-only role can list. The probe has a unique name under {@value #ACCESS_CHECK_PREFIX}, which
     * no import key starts with, so the pods do not disturb each other; one left behind by a pod killed
     * half-way is removed by the lifecycle rule of the bucket.
     */
    @Override
    public void checkAccess() {
        String probe = ACCESS_CHECK_PREFIX + UUID.randomUUID();
        try {
            long generation = createIfAbsent(probe, ACCESS_CHECK_CLAIM_ID, false)
                    .orElseThrow(() -> new IdempotentRepositoryException(location(probe) + " already exists"));
            try {
                if (!confirm(probe, generation)) {
                    throw new IdempotentRepositoryException(location(probe) + " disappeared during the access check");
                }
            } catch (IdempotentRepositoryException e) {
                deleteProbeQuietly(probe, generation);
                throw e;
            }
            deleteIfGeneration(probe, generation);
        } catch (IdempotentRepositoryException e) {
            throw new IdempotentRepositoryException("The bucket of the idempotent repository gs://" + bucket
                    + " is not writable: " + e.getMessage() + (e.getCause() == null ? "" : ": " + e.getCause().getMessage()), e);
        }
    }

    private void deleteProbeQuietly(String probe, long generation) {
        try {
            deleteIfGeneration(probe, generation);
        } catch (IdempotentRepositoryException e) {
            LOGGER.warn("Could not delete {} after a failed access check: {}", location(probe), e.toString());
        }
    }

    @Override
    public String location(String name) {
        return "gs://" + bucket + "/" + name;
    }
}
