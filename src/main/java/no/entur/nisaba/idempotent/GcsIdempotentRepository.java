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
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The idempotent repository on a {@link MarkerStore}: the first create of the key wins, synchronously, on
 * every pod.
 * <p>
 * A claim that is never confirmed (the pod died between the claim and the publish) is taken over once it is
 * older than {@code staleAfter}: the dataset is published late instead of never. Until then the key is
 * neither claimed nor reported as a duplicate: the claim fails with {@link ClaimInProgressException}, so that
 * the message is redelivered until the claim is confirmed, released or stale.
 * <p>
 * A claim whose release failed heals the same way, or sooner on the same pod: the repository remembers it and
 * a retry owns it again.
 * <p>
 * The confirm is retried here: the GCS client does not retry a metadata update that carries no metageneration
 * precondition, and an unconfirmed claim of a published event is taken over and published a second time.
 */
public final class GcsIdempotentRepository implements IdempotentRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GcsIdempotentRepository.class);

    /** Creates and takeovers for one key before giving up on a key that keeps changing under us. */
    private static final int MAX_ATTEMPTS = 3;

    /** Confirms of one claim before giving up; setting the flag on a given generation is idempotent. */
    static final int CONFIRM_ATTEMPTS = 4;
    private static final Duration CONFIRM_RETRY_DELAY = Duration.ofMillis(500);

    private final MarkerStore store;
    private final String instanceId;
    private final Duration staleAfter;
    private final Clock clock;
    private final Duration confirmRetryDelay;

    /** The claims of this pod whose release failed, by import key: a retry on this pod owns them again. */
    private final Map<String, Claim> unreleased = new ConcurrentHashMap<>();

    public GcsIdempotentRepository(MarkerStore store, String instanceId, Duration staleAfter, Clock clock) {
        this(store, instanceId, staleAfter, clock, CONFIRM_RETRY_DELAY);
    }

    /**
     * @param confirmRetryDelay the wait before the first retry of a failed confirm, doubled at each retry.
     */
    GcsIdempotentRepository(MarkerStore store, String instanceId, Duration staleAfter, Clock clock, Duration confirmRetryDelay) {
        this.store = store;
        this.instanceId = instanceId;
        this.staleAfter = staleAfter;
        this.clock = clock;
        this.confirmRetryDelay = confirmRetryDelay;
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
            Claim own = unreleased.remove(importKey);
            if (own != null && own.generation() == found.generation()) {
                LOGGER.warn("Claim {} for key {} could not be released earlier and is still in place; owning it again", own.claimId(), importKey);
                return Optional.of(own);
            }
            Duration age = Duration.between(found.createdAt(), clock.instant());
            if (age.compareTo(staleAfter) < 0) {
                LOGGER.info("Key {} is being published by claim {} since {}; not claiming it", importKey, found.claimId(), found.createdAt());
                throw new ClaimInProgressException("Key " + importKey + " is claimed by " + found.claimId() + " since "
                        + found.createdAt() + " and neither confirmed nor released; it is taken over after " + staleAfter);
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
        Duration delay = confirmRetryDelay;
        for (int attempt = 1; ; attempt++) {
            try {
                if (!store.confirm(claim.importKey(), claim.generation())) {
                    LOGGER.warn("Could not confirm claim {} for key {}: the claim was replaced or deleted; "
                            + "the key may be published again", claim.claimId(), claim.importKey());
                } else if (attempt > 1) {
                    LOGGER.info("Confirmed claim {} for key {} at attempt {}", claim.claimId(), claim.importKey(), attempt);
                }
                return;
            } catch (RuntimeException e) {
                if (attempt == CONFIRM_ATTEMPTS || !pause(delay)) {
                    LOGGER.warn("Could not confirm claim {} for key {} in {} attempt(s): {}. The key may be taken over "
                            + "after {} and the event published twice", claim.claimId(), claim.importKey(), attempt,
                            e.toString(), staleAfter);
                    return;
                }
                LOGGER.info("Could not confirm claim {} for key {}: {}. Trying again", claim.claimId(), claim.importKey(), e.toString());
                delay = delay.multipliedBy(2);
            }
        }
    }

    /**
     * @return false when interrupted: the caller gives up.
     */
    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
            unreleased.put(claim.importKey(), claim);
            LOGGER.error("Could not release key {} (claim {}): {}. A retry on this pod owns the claim again, another pod "
                    + "takes it over after {}; or delete {} by hand", claim.importKey(), claim.claimId(), e.toString(),
                    staleAfter, store.location(claim.importKey()));
        }
    }
}
