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
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the bytes on the idempotent topic with literals, never through the constants being pinned: the Camel
 * version still running during a rollout, and any rollback, reads exactly this.
 */
class KafkaWireContractTest {

    @RegisterExtension
    static final EmbeddedKafkaExtension KAFKA = new EmbeddedKafkaExtension();

    @Test
    void recordValuesAndHeaderName() {
        assertEquals("add", IdempotentTopicContract.ACTION_ADD);
        assertEquals("remove", IdempotentTopicContract.ACTION_REMOVE);
        assertEquals("clear", IdempotentTopicContract.ACTION_CLEAR);
        assertEquals(Set.of("add", "remove", "clear"),
                EnumSet.allOf(IdempotentTopicContract.CacheAction.class).stream().map(Enum::name).collect(Collectors.toSet()));
        assertEquals("nisaba-claim-id", IdempotentTopicContract.CLAIM_ID_HEADER);
        assertEquals("nisaba-idempotent-repo", IdempotentTopicContract.CLIENT_ID_REPOSITORY);
        assertEquals("nisaba-event", IdempotentTopicContract.CLIENT_ID_EVENT_PRODUCER);
        assertEquals("nisaba-event-reader", IdempotentTopicContract.CLIENT_ID_EVENT_READER);
    }

    @Test
    void bytesOnTheTopic() {
        String topic = "idempotent-wire";
        KAFKA.createTopic(topic, 1);
        Map<String, Object> props = KAFKA.clientProperties();
        KafkaClaimRepository repository = new KafkaClaimRepository(topic, props, props, "pod",
                new KafkaClaimRepository.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMillis(100)));
        repository.start();
        try {
            Claim claim = repository.claim("avi_2021-04-21T11_51_59.123").orElseThrow();
            repository.release(claim);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> KAFKA.recordCount(topic) == 2);
        } finally {
            repository.stop();
        }
        List<ConsumerRecord<byte[], byte[]>> records = KAFKA.readAll(topic);
        assertEquals(2, records.size());
        assertArrayEquals("avi_2021-04-21T11_51_59.123".getBytes(StandardCharsets.UTF_8), records.get(0).key());
        assertArrayEquals("add".getBytes(StandardCharsets.UTF_8), records.get(0).value());
        assertArrayEquals("avi_2021-04-21T11_51_59.123".getBytes(StandardCharsets.UTF_8), records.get(1).key());
        assertArrayEquals("remove".getBytes(StandardCharsets.UTF_8), records.get(1).value());
        for (ConsumerRecord<byte[], byte[]> record : records) {
            assertNotNull(record.value(), "a null value would crash the Camel reader on replay");
            List<String> headerNames = Arrays.stream(record.headers().toArray()).map(Header::key).toList();
            assertEquals(List.of("nisaba-claim-id"), headerNames);
            assertEquals("pod:", new String(record.headers().lastHeader("nisaba-claim-id").value(), StandardCharsets.UTF_8).substring(0, 4));
        }
    }
}
