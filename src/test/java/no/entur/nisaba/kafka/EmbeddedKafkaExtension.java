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

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One embedded KRaft broker per test class. Topics are never auto-created: every test creates its own.
 */
public final class EmbeddedKafkaExtension implements BeforeAllCallback, AfterAllCallback {

    private EmbeddedKafkaKraftBroker broker;

    @Override
    public void beforeAll(ExtensionContext context) {
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.brokerProperty("auto.create.topics.enable", "false");
        broker.afterPropertiesSet();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (broker != null) {
            broker.destroy();
        }
    }

    public String bootstrapServers() {
        return broker.getBrokersAsString();
    }

    public void createTopic(String name, int partitions) {
        broker.addTopics(new NewTopic(name, partitions, (short) 1));
    }

    public Map<String, Object> clientProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        return props;
    }

    /**
     * A producer writing String records without headers, as the Camel version did.
     */
    public KafkaProducer<String, String> rawProducer() {
        Map<String, Object> props = clientProperties();
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // as Camel's KafkaIdempotentRepository configured it: acks=1, which disables idempotence
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");
        return new KafkaProducer<>(props);
    }

    /**
     * Every record of the topic, in offset order per partition, as raw bytes.
     */
    public List<ConsumerRecord<byte[], byte[]>> readAll(String topic) {
        Map<String, Object> props = clientProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<byte[], byte[]>> result = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(200));
                records.forEach(result::add);
            }
        }
        return result;
    }

    public long recordCount(String topic) {
        return readAll(topic).size();
    }
}
