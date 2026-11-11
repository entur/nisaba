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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotentTopicStateTest {

    private static final String TOPIC = "idempotent";

    private final IdempotentTopicState state = new IdempotentTopicState();

    private void apply(String key, String value, String claimId) {
        state.apply(key, value, claimId, TOPIC, 0, 0);
    }

    @Test
    void firstAddAfterLastRemoveWins() {
        apply("k", "add", "A");
        apply("k", "add", "B");
        assertEquals(Optional.of("A"), Optional.ofNullable(state.snapshot().get("k")));

        apply("k", "remove", "A");
        assertFalse(state.snapshot().containsKey("k"));

        apply("k", "add", "B2");
        assertEquals(Optional.of("B2"), Optional.ofNullable(state.snapshot().get("k")));
    }

    @Test
    void camelRecordsWithoutClaimIdAreOwnedByNobody() {
        apply("k", "add", null);
        assertEquals(Optional.of(IdempotentTopicState.UNKNOWN_OWNER), Optional.ofNullable(state.snapshot().get("k")));
        apply("k", "add", "A");
        assertEquals(Optional.of(IdempotentTopicState.UNKNOWN_OWNER), Optional.ofNullable(state.snapshot().get("k")));
        apply("k", "remove", null);
        assertFalse(state.snapshot().containsKey("k"));
    }

    @Test
    void removeWithAClaimIdOnlyRemovesThatClaim() {
        apply("k", "add", "A");
        apply("k", "remove", "B");
        assertEquals(Optional.of("A"), Optional.ofNullable(state.snapshot().get("k")), "a late remove of another claim is ignored");
        apply("k", "remove", "A");
        assertFalse(state.snapshot().containsKey("k"));
        apply("k", "add", "C");
        apply("k", "remove", null);
        assertFalse(state.snapshot().containsKey("k"), "a Camel-written remove is unconditional");
    }

    @Test
    void clearEmptiesTheView() {
        apply("k1", "add", null);
        apply("k2", "add", "A");
        apply(null, "clear", null);
        assertEquals(0, state.snapshot().size());
    }

    @Test
    void unknownAndNullValuesAreIgnored() {
        apply("k", "bogus", null);
        apply("k", null, null);
        assertFalse(state.snapshot().containsKey("k"));
        apply("k", "add", null);
        apply("k", "confirm", null);
        assertTrue(state.snapshot().containsKey("k"));
    }

    @Test
    void snapshotIsACopyOfThePresentKeys() {
        apply("k1", "add", null);
        apply("k2", "add", "A");
        apply("k3", "add", "B");
        apply("k3", "remove", "B");
        Map<String, String> snapshot = state.snapshot();
        assertEquals(Map.of("k1", IdempotentTopicState.UNKNOWN_OWNER, "k2", "A"), snapshot);
        apply("k4", "add", "C");
        assertEquals(2, snapshot.size());
    }
}
