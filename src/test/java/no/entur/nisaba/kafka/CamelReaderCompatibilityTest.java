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

import no.entur.nisaba.kafka.ImportEventClaimRepository.Claim;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The records the new writer produces must be readable by the Camel version still deployed during a rollout
 * and after a rollback. {@link #camelAddToCache} is a copy of the decision logic of camel-kafka 4.10.9
 * {@code KafkaIdempotentRepository.addToCache}: it looks only at the key and the value.
 */
class CamelReaderCompatibilityTest {

    @RegisterExtension
    static final EmbeddedKafkaExtension KAFKA = new EmbeddedKafkaExtension();

    private enum CamelCacheAction {
        add,
        remove,
        clear
    }

    /**
     * camel-kafka 4.10.9, KafkaIdempotentRepository.addToCache, minus logging. Note the NPE on a null value.
     */
    static void camelAddToCache(Map<String, Object> cache, String key, String value) {
        try {
            CamelCacheAction action = CamelCacheAction.valueOf(value);
            if (action == CamelCacheAction.add) {
                cache.put(key, key);
            } else if (action == CamelCacheAction.remove) {
                cache.remove(key);
            } else if (action == CamelCacheAction.clear) {
                cache.clear();
            } else {
                throw new IllegalArgumentException("Unknown action");
            }
        } catch (IllegalArgumentException ignored) {
            // Camel logs a WARN and ignores the record
        }
    }

    @Test
    void camelReadsWhatTheNewVersionWrites() {
        String topic = "idempotent-camel-compat";
        KAFKA.createTopic(topic, 1);
        Map<String, Object> props = KAFKA.clientProperties();
        KafkaClaimRepository repository = new KafkaClaimRepository(topic, props, props, "pod",
                new KafkaClaimRepository.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMillis(100)));
        repository.start();
        try {
            Claim first = repository.claim("k1").orElseThrow();
            repository.release(first);
            Claim second = repository.claim("k1").orElseThrow();
            repository.confirm(second);
            Claim other = repository.claim("k2").orElseThrow();
            repository.release(other);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> KAFKA.recordCount(topic) == 5);
        } finally {
            repository.stop();
        }
        Map<String, Object> camelCache = new HashMap<>();
        List<ConsumerRecord<byte[], byte[]>> records = KAFKA.readAll(topic);
        for (ConsumerRecord<byte[], byte[]> record : records) {
            camelAddToCache(camelCache, new String(record.key(), StandardCharsets.UTF_8), new String(record.value(), StandardCharsets.UTF_8));
        }
        assertEquals(Map.of("k1", "k1"), camelCache);
    }

    @Test
    void camelCrashesOnANullValueWhichIsWhyNoneIsEverWritten() {
        assertThrows(NullPointerException.class, () -> camelAddToCache(new HashMap<>(), "k", null));
        Map<String, Object> cache = new HashMap<>();
        camelAddToCache(cache, "k", "confirm");
        assertTrue(cache.isEmpty(), "an unknown value is ignored");
    }
}
