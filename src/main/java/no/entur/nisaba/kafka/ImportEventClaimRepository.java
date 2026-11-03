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

package no.entur.nisaba.kafka;

import java.util.Optional;

/**
 * Decides, atomically across all nisaba pods, whether a dataset import key is new.
 * <p>
 * Replaces Camel's {@code idempotentConsumer} over a {@code KafkaIdempotentRepository}, which was a
 * per-pod cache and therefore needed a leader-elected single consumer.
 */
public interface ImportEventClaimRepository {

    /**
     * Claim the key for publication.
     *
     * @return the claim when this call owns the key and the caller must publish the event; empty when an
     * event has already been sent for this key (or another pod is publishing it).
     * @throws ClaimException when the outcome could not be established; the caller must retry or nack.
     */
    Optional<Claim> claim(String importKey);

    /**
     * Record that the event for this claim has been published. Local bookkeeping only.
     */
    void confirm(Claim claim);

    /**
     * Undo a claim whose publication failed, so that a redelivery can publish the event. Only the owner
     * of the claim may release it.
     */
    void release(Claim claim);

    record Claim(String importKey, String claimId) {
    }
}
