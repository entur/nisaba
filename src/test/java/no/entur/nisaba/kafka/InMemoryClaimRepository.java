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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The claim repository of the Spring tests: one JVM, no Kafka.
 */
public class InMemoryClaimRepository implements ImportEventClaimRepository {

    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private int sequence;

    @Override
    public synchronized Optional<Claim> claim(String importKey) {
        if (owners.containsKey(importKey)) {
            return Optional.empty();
        }
        String claimId = "test:" + (++sequence);
        owners.put(importKey, claimId);
        return Optional.of(new Claim(importKey, claimId));
    }

    @Override
    public void confirm(Claim claim) {
        // nothing to record
    }

    @Override
    public synchronized void release(Claim claim) {
        owners.remove(claim.importKey(), claim.claimId());
    }

    public boolean contains(String importKey) {
        return owners.containsKey(importKey);
    }

    public void clear() {
        owners.clear();
    }
}
