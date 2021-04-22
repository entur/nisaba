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

package no.entur.nisaba.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.processor.idempotent.kafka.KafkaIdempotentRepository;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.Properties;

@Configuration
public class CamelConfig {


    /**
     * Store the key of previously imported datasets and detect duplicates.
     *
     * @param idempotentTopic the topic used to store the history of NeTEx import event.
     * @param brokers         the Kafka brokers.
     * @return an idempotent repository that ientifies duplicate dataset import events.
     */
    @Bean("netexImportEventIdempotentRepo")
    @Profile("!test")
    IdempotentRepository kafkaIdempotentRepository(@Value("${nisaba.kafka.topic.idempotent}") String idempotentTopic,
                                                   @Value("${camel.component.kafka.brokers}") String brokers,
                                                   @Value("${camel.component.kafka.sasl-jaas-config:}") String jaasConfig) {

        KafkaConfiguration config = new KafkaConfiguration();

        Properties commonProperties = new Properties();
        commonProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        commonProperties.put(ProducerConfig.CLIENT_ID_CONFIG, "nisaba-idempotent-repo");
        commonProperties.put(ProducerConfig.RETRIES_CONFIG, "10");
        commonProperties.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "100");

        if(StringUtils.hasText(jaasConfig)) {
            commonProperties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            commonProperties.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
            commonProperties.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);
        }

        Properties producerProperties = config.createProducerProperties();
        producerProperties.putAll(commonProperties);

        Properties consumerProperties = config.createConsumerProperties();
        consumerProperties.putAll(commonProperties);

        return new KafkaIdempotentRepository(idempotentTopic, consumerProperties, commonProperties);
    }


    /**
     * Filter out all headers before sending a message to Kafka.
     *
     * @return a header filter strategy that filters out all headers in a Camel message.
     */
    @Bean("kafkaFilterAllHeadersFilterStrategy")
    public HeaderFilterStrategy kafkaFilterAllHeaderFilterStrategy() {
        return new HeaderFilterStrategy() {


            @Override
            public boolean applyFilterToCamelHeaders(String headerName, Object headerValue, Exchange exchange) {
                return true;
            }

            @Override
            public boolean applyFilterToExternalHeaders(String headerName, Object headerValue, Exchange exchange) {
                return true;
            }
        };
    }

    /**
     * Register Java Time Module for JSON serialization/deserialization of Java Time objects.
     *
     * @return a Jackson module for  JSON serialization/deserialization of Java Time objects.
     */
    @Bean("jacksonJavaTimeModule")
    JavaTimeModule jacksonJavaTimeModule() {
        return new JavaTimeModule();
    }


}
