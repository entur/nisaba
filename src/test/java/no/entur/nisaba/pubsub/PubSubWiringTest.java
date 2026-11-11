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

package no.entur.nisaba.pubsub;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import no.entur.nisaba.TestApp;
import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.kafka.KafkaTestDoubles;
import no.entur.nisaba.kafka.RecordingNetexImportEventPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PubSubEmulatorContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The only test that starts a PubSub emulator, and it checks only the transport: a notification published to
 * Marduk's queue reaches the pipeline and ends as an event, with the correlation id attribute honoured.
 * Topic and subscription are created here, because the consumer never creates them (they are terraformed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestApp.class,
        properties = {"nisaba.pubsub.consumer.enabled=true"})
@ActiveProfiles({"test", "in-memory-blobstore"})
@Import(KafkaTestDoubles.class)
// closed rather than left in the context cache: the PubSub clients hold non-daemon gax threads
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PubSubWiringTest {

    private static final String PROJECT = "test";
    private static final String QUEUE = "NetexExportNotificationQueue";

    private static PubSubEmulatorContainer pubsubEmulator;

    @Autowired
    private PubSubTemplate pubSubTemplate;

    @Autowired
    private Map<String, Map<String, byte[]>> blobsInContainers;

    @Autowired
    private RecordingNetexImportEventPublisher publisher;

    @BeforeAll
    static void startEmulatorAndCreateDestinations() throws IOException {
        pubsubEmulator = new PubSubEmulatorContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk:emulators"));
        pubsubEmulator.start();
        ManagedChannel channel = ManagedChannelBuilder.forTarget(pubsubEmulator.getEmulatorEndpoint()).usePlaintext().build();
        try {
            TransportChannelProvider channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
            try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider).setCredentialsProvider(NoCredentialsProvider.create()).build());
                 SubscriptionAdminClient subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                         .setTransportChannelProvider(channelProvider).setCredentialsProvider(NoCredentialsProvider.create()).build())) {
                topics.createTopic(TopicName.of(PROJECT, QUEUE));
                subscriptions.createSubscription(SubscriptionName.of(PROJECT, QUEUE), TopicName.of(PROJECT, QUEUE),
                        PushConfig.getDefaultInstance(), 60);
            }
        } finally {
            channel.shutdownNow();
        }
    }

    @AfterAll
    static void stopEmulator() {
        pubsubEmulator.stop();
    }

    @DynamicPropertySource
    static void emulatorProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gcp.pubsub.emulator-host", pubsubEmulator::getEmulatorEndpoint);
    }

    @Test
    void aNotificationOnMardukQueueEndsAsAnImportEvent() throws IOException {
        byte[] zip = getClass().getResourceAsStream("/no/entur/nisaba/netex/import/rb_avi-aggregated-netex.zip").readAllBytes();
        blobsInContainers.computeIfAbsent("marduk", k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put("outbound/netex/rb_avi-aggregated-netex.zip", zip);

        pubSubTemplate.publish(QUEUE, "avi", Map.of("RutebankenCorrelationId", "corr-from-marduk"));

        await().atMost(2, TimeUnit.MINUTES).pollInterval(Duration.ofSeconds(1)).until(() -> !publisher.published().isEmpty());
        RecordingNetexImportEventPublisher.Published published = publisher.published().getFirst();
        NetexImportEvent event = published.event();
        assertEquals("avi", event.getCodespace().toString());
        assertEquals("2021-04-13T09:09:45.409", event.getImportDateTime().toString());
        assertEquals("avi_2021-04-13T09_09_45.409", event.getImportKey().toString());
        assertEquals("corr-from-marduk", published.mdc().get("correlationId"));
        assertEquals("avi", published.mdc().get("codespace"));
    }
}
