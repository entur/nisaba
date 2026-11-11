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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * The three Kafka clients, built from Spring Boot's {@code spring.kafka.*} properties so that the brokers, SASL
 * and schema-registry settings are configured once.
 * <p>
 * They are plain Kafka clients rather than spring-kafka's factories: the factories rewrite {@code client.id},
 * and the replay-from-the-beginning readers have no listener-container equivalent.
 */
@Configuration
@EnableConfigurationProperties(NisabaKafkaProperties.class)
public class NisabaKafkaConfig {

    @Bean
    PublishedImportKeys publishedImportKeys() {
        return new PublishedImportKeys();
    }

    @Bean
    @Profile("!test")
    KafkaNetexImportEventPublisher kafkaNetexImportEventPublisher(KafkaProperties kafkaProperties, NisabaKafkaProperties nisabaKafkaProperties,
                                                                  PublishedImportKeys publishedImportKeys) {
        return new KafkaNetexImportEventPublisher(nisabaKafkaProperties.topic().event(),
                kafkaProperties.buildProducerProperties(), nisabaKafkaProperties.sendTimeout(),
                publishedImportKeys, nisabaKafkaProperties.publishVerificationTimeout());
    }

    @Bean
    @Profile("!test")
    ImportDateMapReader importDateMapReader(KafkaProperties kafkaProperties, NisabaKafkaProperties nisabaKafkaProperties,
                                            @Qualifier("importDatesMap") Map<String, String> importDatesMap,
                                            PublishedImportKeys publishedImportKeys) {
        return new ImportDateMapReader(nisabaKafkaProperties.topic().event(), kafkaProperties.buildConsumerProperties(),
                importDatesMap, publishedImportKeys, nisabaKafkaProperties.pollTimeout(), nisabaKafkaProperties.replayTimeout());
    }
}
