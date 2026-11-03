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

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
        CompletableFuture<Boolean> a = state.expectAdd("A");
        CompletableFuture<Boolean> b = state.expectAdd("B");
        apply("k", "add", "A");
        apply("k", "add", "B");
        assertTrue(a.join());
        assertFalse(b.join());
        assertEquals(Optional.of("A"), state.owner("k"));

        CompletableFuture<Void> removed = state.expectRemove("A");
        apply("k", "remove", "A");
        assertTrue(removed.isDone());
        assertFalse(state.contains("k"));

        CompletableFuture<Boolean> b2 = state.expectAdd("B2");
        apply("k", "add", "B2");
        assertTrue(b2.join());
        assertEquals(Optional.of("B2"), state.owner("k"));
    }

    @Test
    void camelRecordsWithoutClaimIdAreOwnedByNobody() {
        apply("k", "add", null);
        assertEquals(Optional.of(IdempotentTopicState.UNKNOWN_OWNER), state.owner("k"));
        CompletableFuture<Boolean> a = state.expectAdd("A");
        apply("k", "add", "A");
        assertFalse(a.join());
        apply("k", "remove", null);
        assertFalse(state.contains("k"));
    }

    @Test
    void removeWithAClaimIdOnlyRemovesThatClaim() {
        apply("k", "add", "A");
        apply("k", "remove", "B");
        assertEquals(Optional.of("A"), state.owner("k"), "a late remove of another claim is ignored");
        apply("k", "remove", "A");
        assertFalse(state.contains("k"));
        apply("k", "add", "C");
        apply("k", "remove", null);
        assertFalse(state.contains("k"), "a Camel-written remove is unconditional");
    }

    @Test
    void clearEmptiesTheView() {
        apply("k1", "add", null);
        apply("k2", "add", "A");
        apply(null, "clear", null);
        assertEquals(0, state.size());
    }

    @Test
    void unknownAndNullValuesAreIgnored() {
        apply("k", "bogus", null);
        apply("k", null, null);
        assertFalse(state.contains("k"));
        apply("k", "add", null);
        apply("k", "confirm", null);
        assertTrue(state.contains("k"));
    }

    @Test
    void forgetDropsPendingFutures() {
        CompletableFuture<Boolean> a = state.expectAdd("A");
        state.forget("A");
        apply("k", "add", "A");
        assertFalse(a.isDone());
        assertEquals(Optional.of("A"), state.owner("k"));
    }

    @Test
    void failPendingCompletesEverythingExceptionally() {
        CompletableFuture<Boolean> a = state.expectAdd("A");
        CompletableFuture<Void> r = state.expectRemove("B");
        state.failPending(new ClaimException("stopped"));
        assertTrue(a.isCompletedExceptionally());
        assertTrue(r.isCompletedExceptionally());
    }
}
