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

package no.entur.nisaba.idempotent.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static no.entur.nisaba.idempotent.migration.IdempotentTopicContract.ACTION_ADD;
import static no.entur.nisaba.idempotent.migration.IdempotentTopicContract.ACTION_CLEAR;
import static no.entur.nisaba.idempotent.migration.IdempotentTopicContract.ACTION_REMOVE;

/**
 * The replayed view of the Kafka idempotent topic of the previous versions: which import keys are present
 * and which claim owns each of them. Only the migration to the GCS idempotent repository reads it.
 * <p>
 * The rule is the one both previous versions applied: the first {@code add} after the last {@code remove}
 * makes the key present. Records written by the Camel version carry no claim id; records written by the
 * Kafka claim repository carry one in the {@value IdempotentTopicContract#CLAIM_ID_HEADER} header.
 */
final class IdempotentTopicState {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentTopicState.class);

    /**
     * Owner of a record written by the Camel version, which carries no claim id.
     */
    static final String UNKNOWN_OWNER = "";

    private final ConcurrentHashMap<String, String> owners = new ConcurrentHashMap<>();

    /**
     * Apply one record of the topic. Never throws on bad input: an unknown or null value is logged and skipped.
     */
    void apply(String key, String value, String claimId, String topic, int partition, long offset) {
        if (value == null) {
            unexpected(key, null, topic, partition, offset);
            return;
        }
        switch (value) {
            case ACTION_ADD -> owners.putIfAbsent(key, claimId == null ? UNKNOWN_OWNER : claimId);
            case ACTION_REMOVE -> {
                // a remove written with a claim id only removes the claim it releases: a remove that landed
                // late cannot delete a newer claim. A remove written by the Camel version removes unconditionally.
                if (claimId == null) {
                    owners.remove(key);
                } else {
                    owners.remove(key, claimId);
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

    /**
     * @return a copy of the keys present, each with the claim id that owns it ({@link #UNKNOWN_OWNER} for a
     * record written by the Camel version).
     */
    Map<String, String> snapshot() {
        return Map.copyOf(owners);
    }
}
