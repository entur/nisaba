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

import no.entur.nisaba.idempotent.IdempotentRepositoryConfig;
import no.entur.nisaba.idempotent.IdempotentRepositoryProperties;
import no.entur.nisaba.idempotent.MarkerStore;
import no.entur.nisaba.kafka.NisabaKafkaProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * One-shot command line tool: copies the Kafka idempotent topic into the GCS idempotent repository and exits
 * (0 when every key is migrated, 1 otherwise). It starts nothing of the application itself: no PubSub
 * consumer, no web server, no event producer; only the marker store and one Kafka consumer. See
 * {@code docs/gcs-idempotent-repository.md} for the command line.
 */
public final class IdempotentRepositoryMigrationApp {

    /**
     * Deliberately not a {@code @Configuration}: the application's component scan must not pick it up.
     */
    @EnableConfigurationProperties({KafkaProperties.class, NisabaKafkaProperties.class})
    @Import(IdempotentRepositoryConfig.class)
    static class MigrationConfig {
    }

    private IdempotentRepositoryMigrationApp() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String... args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MigrationConfig.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            NisabaKafkaProperties kafka = context.getBean(NisabaKafkaProperties.class);
            IdempotentRepositoryProperties properties = context.getBean(IdempotentRepositoryProperties.class);
            IdempotentRepositoryMigration migration = new IdempotentRepositoryMigration(kafka.topic().idempotent(),
                    context.getBean(KafkaProperties.class).buildConsumerProperties(), context.getBean(MarkerStore.class),
                    properties.migration().dryRun(), properties.migration().threads(), kafka.pollTimeout(), kafka.replayTimeout());
            return migration.run().successful() ? 0 : 1;
        }
    }
}
