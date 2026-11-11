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

import java.util.Optional;

/**
 * The idempotent repository of published datasets: one claim per import key, atomic across pods.
 */
public interface IdempotentRepository {

    /**
     * Claim the key for publication.
     *
     * @return the claim when this call owns the key and the caller must publish the event; empty when an
     * event has already been sent for this key (or another pod is publishing it).
     * @throws IdempotentRepositoryException when the outcome could not be established; the caller must retry or nack.
     */
    Optional<Claim> claim(String importKey);

    /**
     * Record that the event for this claim has been published. Never throws: the event is out, and a failure
     * here only means the key may be taken over once the claim is stale.
     */
    void confirm(Claim claim);

    /**
     * Undo a claim whose publication failed, so that a redelivery can publish the event. Only the owner
     * of the claim may release it. Never throws.
     */
    void release(Claim claim);

    /**
     * @param importKey  the key claimed
     * @param claimId    identifies the claimer: {@code <pod name>:<uuid>}
     * @param generation the ownership token of the stored claim, needed to confirm or release it
     */
    record Claim(String importKey, String claimId, long generation) {
    }
}
