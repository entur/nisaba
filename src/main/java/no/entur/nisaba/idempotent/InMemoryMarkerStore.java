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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The marker store of a single JVM, for the local profiles and the tests: the same semantics as
 * {@link GcsMarkerStore}, backed by a map.
 */
public final class InMemoryMarkerStore implements MarkerStore {

    private record Entry(long generation, String claimId, boolean confirmed, Instant createdAt) {
        Marker stored() {
            return new Marker(generation, claimId, confirmed, createdAt);
        }
    }

    private final ConcurrentHashMap<String, Entry> objects = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final Clock clock;

    public InMemoryMarkerStore() {
        this(Clock.systemUTC());
    }

    public InMemoryMarkerStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OptionalLong createIfAbsent(String name, String claimId, boolean confirmed) {
        long generation = generations.incrementAndGet();
        Entry previous = objects.putIfAbsent(name, new Entry(generation, claimId, confirmed, clock.instant()));
        return previous == null ? OptionalLong.of(generation) : OptionalLong.empty();
    }

    @Override
    public Optional<Marker> read(String name) {
        return Optional.ofNullable(objects.get(name)).map(Entry::stored);
    }

    @Override
    public boolean confirm(String name, long generation) {
        AtomicBoolean matched = new AtomicBoolean();
        objects.computeIfPresent(name, (k, entry) -> {
            if (entry.generation != generation) {
                return entry;
            }
            matched.set(true);
            return new Entry(entry.generation, entry.claimId, true, entry.createdAt);
        });
        return matched.get();
    }

    @Override
    public boolean deleteIfGeneration(String name, long generation) {
        AtomicBoolean matched = new AtomicBoolean();
        objects.computeIfPresent(name, (k, entry) -> {
            if (entry.generation != generation) {
                return entry;
            }
            matched.set(true);
            return null;
        });
        return matched.get();
    }

    @Override
    public void checkAccess() {
        // always accessible
    }

    @Override
    public String location(String name) {
        return "memory:" + name;
    }

    // --- test hooks ---

    public boolean contains(String name) {
        return objects.containsKey(name);
    }

    public int size() {
        return objects.size();
    }

    public void clear() {
        objects.clear();
    }

    /**
     * Make the claim for the key look older, as if it had been created {@code by} earlier.
     */
    public void backdate(String name, Duration by) {
        objects.computeIfPresent(name, (k, entry) ->
                new Entry(entry.generation, entry.claimId, entry.confirmed, entry.createdAt.minus(by)));
    }
}
