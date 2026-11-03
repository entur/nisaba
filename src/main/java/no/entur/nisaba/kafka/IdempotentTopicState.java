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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static no.entur.nisaba.kafka.IdempotentTopicContract.ACTION_ADD;
import static no.entur.nisaba.kafka.IdempotentTopicContract.ACTION_CLEAR;
import static no.entur.nisaba.kafka.IdempotentTopicContract.ACTION_REMOVE;

/**
 * The in-memory view of the idempotent topic: which import keys are present and which claim owns each of them.
 * <p>
 * Records are applied in topic order by the poller thread. Kafka orders the records of one key within one
 * partition, so "the first {@code add} after the last {@code remove} wins" is the same answer on every pod.
 * A claim learns its outcome at the moment its own record is applied, through the future registered with
 * {@link #expectAdd(String)}: that is what makes the decision independent of what is applied afterwards.
 * <p>
 * Thread-safe. Contains no Kafka code so that the rules can be unit tested.
 */
public final class IdempotentTopicState {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentTopicState.class);

    /**
     * Owner of a record written by the Camel version, which carries no claim id.
     */
    public static final String UNKNOWN_OWNER = "";

    private final ConcurrentHashMap<String, String> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingAdds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingRemoves = new ConcurrentHashMap<>();

    /**
     * Register interest in the outcome of the {@code add} record carrying this claim id.
     *
     * @return a future completed with true when the add made the key present (the claim won) and with false
     * when the key was already present.
     */
    public CompletableFuture<Boolean> expectAdd(String claimId) {
        return pendingAdds.computeIfAbsent(claimId, id -> new CompletableFuture<>());
    }

    /**
     * Register interest in the application of the {@code remove} record carrying this claim id.
     */
    public CompletableFuture<Void> expectRemove(String claimId) {
        return pendingRemoves.computeIfAbsent(claimId, id -> new CompletableFuture<>());
    }

    public void forget(String claimId) {
        pendingAdds.remove(claimId);
        pendingRemoves.remove(claimId);
    }

    /**
     * Apply one record of the topic. Never throws on bad input: an unknown or null value is logged and skipped,
     * as the Camel reader does (except that Camel NPEs on a null value).
     */
    public void apply(String key, String value, String claimId, String topic, int partition, long offset) {
        if (value == null) {
            unexpected(key, null, topic, partition, offset);
            return;
        }
        switch (value) {
            case ACTION_ADD -> {
                String previous = owners.putIfAbsent(key, claimId == null ? UNKNOWN_OWNER : claimId);
                boolean won = previous == null;
                LOGGER.debug("Applied add for key {} (claim {}): {}", key, claimId, won ? "present" : "already present");
                if (claimId != null) {
                    CompletableFuture<Boolean> pending = pendingAdds.remove(claimId);
                    if (pending != null) {
                        pending.complete(won);
                    }
                }
            }
            case ACTION_REMOVE -> {
                // a remove written by this version only removes the claim it releases: a remove that lands
                // late (its acknowledgement was lost and it was sent again) cannot delete a newer claim.
                // A remove written by the Camel version carries no claim id and removes unconditionally.
                boolean removed = claimId == null ? owners.remove(key) != null : owners.remove(key, claimId);
                LOGGER.debug("Applied remove for key {} (claim {}): {}", key, claimId, removed ? "removed" : "not the owner, ignored");
                if (claimId != null) {
                    CompletableFuture<Void> pending = pendingRemoves.remove(claimId);
                    if (pending != null) {
                        pending.complete(null);
                    }
                }
            }
            case ACTION_CLEAR -> {
                owners.clear();
                LOGGER.info("Applied clear of the idempotent repository");
            }
            default -> unexpected(key, value, topic, partition, offset);
        }
    }

    private static void unexpected(String key, String value, String topic, int partition, long offset) {
        LOGGER.warn("Unexpected action value:\"{}\" for key:\"{}\" received on [topic:{}, partition:{}, offset:{}]. Ignoring.",
                value, key, topic, partition, offset);
    }

    public boolean contains(String key) {
        return owners.containsKey(key);
    }

    public Optional<String> owner(String key) {
        return Optional.ofNullable(owners.get(key));
    }

    public int size() {
        return owners.size();
    }

    /**
     * Number of futures still waiting for a record; zero when nothing is in flight.
     */
    public int pendingCount() {
        return pendingAdds.size() + pendingRemoves.size();
    }

    /**
     * Fail every pending future, on shutdown.
     */
    public void failPending(Throwable cause) {
        pendingAdds.values().forEach(f -> f.completeExceptionally(cause));
        pendingAdds.clear();
        pendingRemoves.values().forEach(f -> f.completeExceptionally(cause));
        pendingRemoves.clear();
    }
}
