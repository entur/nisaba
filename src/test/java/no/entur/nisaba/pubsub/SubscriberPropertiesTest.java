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

import com.google.api.gax.batching.FlowControlSettings;
import com.google.cloud.spring.pubsub.support.DefaultSubscriberFactory;
import no.entur.nisaba.TestApp;
import no.entur.nisaba.kafka.KafkaTestDoubles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The subscriber settings must reach the object that creates the subscriber for Marduk's subscription, which
 * lives in another project than {@code spring.cloud.gcp.project-id}. The test properties set non-default values
 * for all three, so the assertions cannot pass vacuously: spring-cloud-gcp defaults the ack extension to 0,
 * which disables deadline extension altogether.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestApp.class)
@ActiveProfiles({"test", "in-memory-blobstore"})
@Import(KafkaTestDoubles.class)
class SubscriberPropertiesTest {

    private static final String SUBSCRIPTION = "projects/ent-marduk-prd/subscriptions/NetexExportNotificationQueue";

    @Autowired
    private DefaultSubscriberFactory subscriberFactory;

    @Autowired
    private ApplicationContext context;

    @Test
    void flowControlAndThreadsAndAckExtensionReachTheSubscriberFactory() throws Exception {
        FlowControlSettings flowControl = subscriberFactory.getFlowControlSettings(SUBSCRIPTION);
        assertEquals(1L, flowControl.getMaxOutstandingElementCount());

        // spring-cloud-gcp runs the subscriber callbacks on this scheduler, sized by executor-threads
        ThreadPoolTaskScheduler scheduler = context.getBean("globalPubSubSubscriberThreadPoolScheduler", ThreadPoolTaskScheduler.class);
        assertEquals(1, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());

        Method maxAckExtension = DefaultSubscriberFactory.class.getDeclaredMethod("getMaxAckExtensionPeriod", String.class);
        maxAckExtension.setAccessible(true);
        org.threeten.bp.Duration period = (org.threeten.bp.Duration) maxAckExtension.invoke(subscriberFactory, SUBSCRIPTION);
        assertEquals(3600, period.getSeconds());
    }
}
