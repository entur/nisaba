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

import no.entur.nisaba.idempotent.IdempotentRepository.Claim;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of the idempotent repository, on the in-memory marker store. Two repositories on one store are
 * two pods.
 */
class GcsIdempotentRepositoryTest {

    private static final String KEY = "avi_2021-04-21T11_51_59.123";
    private static final Duration STALE_AFTER = Duration.ofMinutes(15);

    /**
     * A store whose calls can be made to fail, or to succeed with the answer lost.
     */
    private static class FaultyStore implements MarkerStore {
        private final InMemoryMarkerStore delegate;
        boolean failCreate;
        boolean loseCreateAnswer;
        boolean failRead;
        boolean failConfirm;
        boolean failDelete;
        Runnable beforeDelete = () -> { };

        private FaultyStore(InMemoryMarkerStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public OptionalLong createIfAbsent(String name, String claimId, boolean confirmed) {
            if (failCreate) {
                throw new IdempotentRepositoryException("create failed");
            }
            OptionalLong generation = delegate.createIfAbsent(name, claimId, confirmed);
            // what the client library reports when it retried a create whose first answer was lost
            return loseCreateAnswer ? OptionalLong.empty() : generation;
        }

        @Override
        public Optional<Marker> read(String name) {
            if (failRead) {
                throw new IdempotentRepositoryException("read failed");
            }
            return delegate.read(name);
        }

        @Override
        public boolean confirm(String name, long generation) {
            if (failConfirm) {
                throw new IdempotentRepositoryException("confirm failed");
            }
            return delegate.confirm(name, generation);
        }

        @Override
        public boolean deleteIfGeneration(String name, long generation) {
            beforeDelete.run();
            if (failDelete) {
                throw new IdempotentRepositoryException("delete failed");
            }
            return delegate.deleteIfGeneration(name, generation);
        }

        @Override
        public void checkAccess() {
            delegate.checkAccess();
        }

        @Override
        public String location(String name) {
            return delegate.location(name);
        }
    }

    private final InMemoryMarkerStore store = new InMemoryMarkerStore();
    private final GcsIdempotentRepository a = repository("a", store);
    private final GcsIdempotentRepository b = repository("b", store);

    private static GcsIdempotentRepository repository(String instanceId, MarkerStore store) {
        return new GcsIdempotentRepository(store, instanceId, STALE_AFTER, Clock.systemUTC());
    }

    @Test
    void firstClaimWinsAndCarriesThePodName() {
        Optional<Claim> claim = a.claim(KEY);
        assertTrue(claim.isPresent());
        assertEquals(KEY, claim.get().importKey());
        assertTrue(claim.get().claimId().startsWith("a:"));
        assertEquals(claim.get().claimId(), store.read(KEY).orElseThrow().claimId());
        assertEquals(claim.get().generation(), store.read(KEY).orElseThrow().generation());
        assertFalse(store.read(KEY).orElseThrow().confirmed());
    }

    @Test
    void confirmedKeyIsADuplicateForEverybodyForEver() {
        a.confirm(a.claim(KEY).orElseThrow());
        assertTrue(store.read(KEY).orElseThrow().confirmed());
        store.backdate(KEY, STALE_AFTER.multipliedBy(1000));
        assertTrue(a.claim(KEY).isEmpty());
        assertTrue(b.claim(KEY).isEmpty());
        assertEquals(1, store.size());
    }

    @Test
    void keyBeingPublishedByAnotherPodIsNeitherClaimedNorADuplicate() {
        Claim first = a.claim(KEY).orElseThrow();
        store.backdate(KEY, STALE_AFTER.minusSeconds(30));
        assertThrows(ClaimInProgressException.class, () -> b.claim(KEY));
        assertEquals(first.generation(), store.read(KEY).orElseThrow().generation());

        // the redelivery is a duplicate once the claimer has confirmed
        a.confirm(first);
        assertTrue(b.claim(KEY).isEmpty());
    }

    @Test
    void redeliveryOfAKeyWhoseClaimerDiedIsPublishedOnceTheClaimIsStale() {
        a.claim(KEY).orElseThrow();
        assertThrows(ClaimInProgressException.class, () -> b.claim(KEY));
        store.backdate(KEY, STALE_AFTER.plusSeconds(1));
        assertTrue(b.claim(KEY).orElseThrow().claimId().startsWith("b:"));
    }

    @Test
    void releasedKeyCanBeClaimedAgain() {
        Claim first = a.claim(KEY).orElseThrow();
        a.release(first);
        assertFalse(store.contains(KEY));
        Claim second = b.claim(KEY).orElseThrow();
        assertTrue(second.claimId().startsWith("b:"));
    }

    @Test
    void unconfirmedClaimIsTakenOverOnceStale() {
        Claim dead = a.claim(KEY).orElseThrow();
        store.backdate(KEY, STALE_AFTER.plusSeconds(1));
        Claim taken = b.claim(KEY).orElseThrow();
        assertTrue(taken.claimId().startsWith("b:"));
        assertEquals(taken.claimId(), store.read(KEY).orElseThrow().claimId());

        // what the dead claimer does afterwards must not touch the new claim
        a.confirm(dead);
        assertFalse(store.read(KEY).orElseThrow().confirmed());
        a.release(dead);
        assertEquals(taken.generation(), store.read(KEY).orElseThrow().generation());

        b.confirm(taken);
        assertTrue(store.read(KEY).orElseThrow().confirmed());
    }

    @Test
    void onlyOneOfManyPodsTakesAStaleClaimOver() throws Exception {
        a.claim(KEY).orElseThrow();
        store.backdate(KEY, STALE_AFTER.plusSeconds(1));
        int pods = 16;
        ExecutorService executor = Executors.newFixedThreadPool(pods);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<Claim>>> futures = new ArrayList<>();
            for (int i = 0; i < pods; i++) {
                GcsIdempotentRepository pod = repository("pod" + i, store);
                Callable<Optional<Claim>> task = () -> {
                    go.await();
                    return pod.claim(KEY);
                };
                futures.add(executor.submit(task));
            }
            go.countDown();
            int winners = 0;
            int inProgress = 0;
            for (Future<Optional<Claim>> future : futures) {
                try {
                    if (future.get().isPresent()) {
                        winners++;
                    }
                } catch (ExecutionException e) {
                    // the losers find the winner's fresh claim: their message is retried, not acknowledged
                    assertInstanceOf(ClaimInProgressException.class, e.getCause());
                    inProgress++;
                }
            }
            assertEquals(1, winners);
            assertEquals(pods - 1, inProgress);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void claimWhoseAnswerWasLostIsOwned() {
        FaultyStore faulty = new FaultyStore(store);
        GcsIdempotentRepository pod = repository("a", faulty);
        faulty.loseCreateAnswer = true;
        Claim claim = pod.claim(KEY).orElseThrow();
        assertEquals(store.read(KEY).orElseThrow().generation(), claim.generation());
        faulty.loseCreateAnswer = false;
        pod.confirm(claim);
        assertTrue(store.read(KEY).orElseThrow().confirmed());
    }

    @Test
    void failedReleaseIsHealedByARetryOnTheSamePod() {
        FaultyStore faulty = new FaultyStore(store);
        GcsIdempotentRepository pod = repository("a", faulty);
        Claim claim = pod.claim(KEY).orElseThrow();
        faulty.failDelete = true;
        pod.release(claim);
        assertTrue(store.contains(KEY), "the release failed without throwing");

        // the retry owns the claim again, and a second failed release is remembered as well
        assertEquals(claim, pod.claim(KEY).orElseThrow());
        pod.release(claim);
        assertEquals(claim, pod.claim(KEY).orElseThrow());

        faulty.failDelete = false;
        pod.confirm(claim);
        assertTrue(store.read(KEY).orElseThrow().confirmed());
        assertTrue(pod.claim(KEY).isEmpty());
    }

    @Test
    void failedReleaseIsHealedByTheTakeoverOnAnotherPod() {
        FaultyStore faulty = new FaultyStore(store);
        GcsIdempotentRepository pod = repository("a", faulty);
        Claim claim = pod.claim(KEY).orElseThrow();
        faulty.failDelete = true;
        pod.release(claim);
        faulty.failDelete = false;

        assertThrows(ClaimInProgressException.class, () -> b.claim(KEY), "not stale yet: the message is retried");
        store.backdate(KEY, STALE_AFTER.plusSeconds(1));
        Claim taken = b.claim(KEY).orElseThrow();

        // the first pod no longer owns the key: its remembered claim is of an older generation
        assertThrows(ClaimInProgressException.class, () -> pod.claim(KEY));
        assertEquals(taken.generation(), store.read(KEY).orElseThrow().generation());
    }

    @Test
    void failedConfirmDoesNotThrow() {
        FaultyStore faulty = new FaultyStore(store);
        GcsIdempotentRepository pod = repository("a", faulty);
        Claim claim = pod.claim(KEY).orElseThrow();
        faulty.failConfirm = true;
        pod.confirm(claim);
        assertFalse(store.read(KEY).orElseThrow().confirmed());
    }

    @Test
    void storeFailureDuringAClaimIsReported() {
        FaultyStore faulty = new FaultyStore(store);
        GcsIdempotentRepository pod = repository("a", faulty);
        faulty.failCreate = true;
        assertThrows(IdempotentRepositoryException.class, () -> pod.claim(KEY));
        faulty.failCreate = false;

        b.claim(KEY).orElseThrow();
        faulty.failRead = true;
        assertThrows(IdempotentRepositoryException.class, () -> pod.claim(KEY));
    }

    @Test
    void keyReleasedWhileBeingClaimedIsClaimedAgain() {
        Claim first = a.claim(KEY).orElseThrow();
        // b's create fails on a's marker, which is gone by the time b reads it
        FaultyStore faulty = new FaultyStore(store) {
            private boolean released;

            @Override
            public Optional<Marker> read(String name) {
                if (!released) {
                    released = true;
                    a.release(first);
                }
                return super.read(name);
            }
        };
        Claim second = repository("b", faulty).claim(KEY).orElseThrow();
        assertTrue(second.claimId().startsWith("b:"));
    }

    @Test
    void keyThatKeepsChangingIsReportedAfterAFewAttempts() {
        // every takeover is beaten by a new stale claim of somebody else
        FaultyStore faulty = new FaultyStore(store);
        faulty.beforeDelete = () -> {
            store.clear();
            store.createIfAbsent(KEY, "other:claim", false);
            store.backdate(KEY, STALE_AFTER.plusSeconds(1));
        };
        store.createIfAbsent(KEY, "other:claim", false);
        store.backdate(KEY, STALE_AFTER.plusSeconds(1));
        assertThrows(IdempotentRepositoryException.class, () -> repository("a", faulty).claim(KEY));
    }
}
