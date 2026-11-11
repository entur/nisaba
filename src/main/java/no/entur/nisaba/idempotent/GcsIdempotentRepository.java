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

import no.entur.nisaba.idempotent.MarkerStore.Marker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The idempotent repository on a {@link MarkerStore}: the first create of the key wins, synchronously, on
 * every pod.
 * <p>
 * A claim that is never confirmed (the pod died between the claim and the publish) is taken over once it is
 * older than {@code staleAfter}: the dataset is published late instead of never. A claim whose release
 * failed heals the same way, or sooner on the same pod: a retry finds its own claim id and owns it again.
 */
public final class GcsIdempotentRepository implements IdempotentRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GcsIdempotentRepository.class);

    /** Creates and takeovers for one key before giving up on a key that keeps changing under us. */
    private static final int MAX_ATTEMPTS = 3;

    private final MarkerStore store;
    private final String instanceId;
    private final Duration staleAfter;
    private final Clock clock;

    public GcsIdempotentRepository(MarkerStore store, String instanceId, Duration staleAfter, Clock clock) {
        this.store = store;
        this.instanceId = instanceId;
        this.staleAfter = staleAfter;
        this.clock = clock;
    }

    @Override
    public Optional<Claim> claim(String importKey) {
        String claimId = instanceId + ':' + UUID.randomUUID();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            OptionalLong generation = store.createIfAbsent(importKey, claimId, false);
            if (generation.isPresent()) {
                LOGGER.info("Claimed key {} with claim {}", importKey, claimId);
                return Optional.of(new Claim(importKey, claimId, generation.getAsLong()));
            }
            Optional<Marker> existing = store.read(importKey);
            if (existing.isEmpty()) {
                LOGGER.info("Key {} was released while being claimed; claiming again", importKey);
                continue;
            }
            Marker found = existing.get();
            if (claimId.equals(found.claimId())) {
                LOGGER.warn("Claim {} for key {} was created although its acknowledgement was lost; owning it", claimId, importKey);
                return Optional.of(new Claim(importKey, claimId, found.generation()));
            }
            if (found.confirmed()) {
                LOGGER.debug("Key {} is already present (claim {}), no claim made", importKey, found.claimId());
                return Optional.empty();
            }
            Duration age = Duration.between(found.createdAt(), clock.instant());
            if (age.compareTo(staleAfter) < 0) {
                LOGGER.info("Key {} is being published by claim {} since {}; not claiming it", importKey, found.claimId(), found.createdAt());
                return Optional.empty();
            }
            LOGGER.warn("Taking over claim {} for key {}: unconfirmed since {}, {} ago", found.claimId(), importKey, found.createdAt(), age);
            if (!store.deleteIfGeneration(importKey, found.generation())) {
                LOGGER.info("Claim {} for key {} was replaced before it could be taken over", found.claimId(), importKey);
            }
        }
        throw new IdempotentRepositoryException("Key " + importKey + " could not be claimed in " + MAX_ATTEMPTS + " attempts: it keeps changing");
    }

    @Override
    public void confirm(Claim claim) {
        try {
            if (!store.confirm(claim.importKey(), claim.generation())) {
                LOGGER.warn("Could not confirm claim {} for key {}: the claim was replaced or deleted; "
                        + "the key may be published again", claim.claimId(), claim.importKey());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not confirm claim {} for key {}: {}. The key may be taken over after {} and the event "
                    + "published twice", claim.claimId(), claim.importKey(), e.toString(), staleAfter);
        }
    }

    @Override
    public void release(Claim claim) {
        try {
            if (store.deleteIfGeneration(claim.importKey(), claim.generation())) {
                LOGGER.warn("Released key {} (claim {}) so that it can be claimed again", claim.importKey(), claim.claimId());
            } else {
                LOGGER.warn("Not releasing key {}: claim {} no longer owns it", claim.importKey(), claim.claimId());
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not release key {} (claim {}): {}. A retry on this pod owns the claim again, another pod "
                    + "takes it over after {}; or delete {} by hand", claim.importKey(), claim.claimId(), e.toString(),
                    staleAfter, store.location(claim.importKey()));
        }
    }
}
