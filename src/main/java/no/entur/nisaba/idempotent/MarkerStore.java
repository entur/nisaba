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

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Where the idempotent repository keeps its markers: one object per import key, created only if absent. The generation returned by the
 * create is the ownership token for the confirm and the delete, so that a stale or late operation can never
 * touch a newer claim of the same key.
 * <p>
 * Every method throws {@link IdempotentRepositoryException} when the store cannot answer.
 */
public interface MarkerStore {

    /**
     * @param generation the ownership token of the object
     * @param claimId    the claimer that created it
     * @param confirmed  whether the claimer reported the event published
     * @param createdAt  when the object was created, by the store's clock
     */
    record Marker(long generation, String claimId, boolean confirmed, Instant createdAt) {
    }

    /**
     * Create the object for the key unless one exists.
     *
     * @return the generation of the new object, or empty when the key is already present
     */
    OptionalLong createIfAbsent(String name, String claimId, boolean confirmed);

    /**
     * @return the stored claim for the key, or empty when the key is absent
     */
    Optional<Marker> read(String name);

    /**
     * Mark the claim confirmed.
     *
     * @return false when the object no longer has this generation (replaced or deleted)
     */
    boolean confirm(String name, long generation);

    /**
     * Delete the claim.
     *
     * @return false when the object no longer has this generation (replaced or deleted)
     */
    boolean deleteIfGeneration(String name, long generation);

    /**
     * Fail when the store is not reachable or not writable, so that a misconfiguration is seen at start-up
     * and not at the next nightly export.
     */
    void checkAccess();

    /**
     * @return where the object for the key lives, for log lines and manual repair
     */
    String location(String name);
}
