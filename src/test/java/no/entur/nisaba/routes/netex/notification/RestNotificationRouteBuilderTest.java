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

package no.entur.nisaba.routes.netex.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.entur.nisaba.NisabaRouteBuilderIntegrationTestBase;
import no.entur.nisaba.TestApp;
import no.entur.nisaba.avro.NetexImportEvent;
import org.apache.camel.Exchange;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, classes = TestApp.class)
class RestNotificationRouteBuilderTest extends NisabaRouteBuilderIntegrationTestBase {

    @Produce("direct:updateImportDateMap")
    protected ProducerTemplate updateImportDateMapProducer;

    @Produce("http:localhost:{{server.port}}/services/timetable-import-info/import_date")
    protected ProducerTemplate importAllDatesProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void mockKafkaProducer() throws Exception {
        // Mock the Kafka producer endpoint to avoid broker configuration issues
        AdviceWith.adviceWith(context, "notify-consumers", a ->
            a.weaveById("to-kafka-topic-event").replace().to("mock:nisabaEventTopic"));
    }

    @Test
    void testGetAllImportDatesViaRest() throws Exception {
        context.start();

        // Add multiple codespaces
        LocalDateTime dateTime1 = LocalDateTime.of(2021, 8, 1, 10, 0, 0);
        LocalDateTime dateTime2 = LocalDateTime.of(2021, 8, 2, 11, 0, 0);
        LocalDateTime dateTime3 = LocalDateTime.of(2021, 8, 3, 12, 0, 0);

        updateImportDateMapProducer.sendBody(createEvent("opp", dateTime1));
        updateImportDateMapProducer.sendBody(createEvent("flt", dateTime2));
        updateImportDateMapProducer.sendBody(createEvent("vyg", dateTime3));

        // Call REST endpoint
        Map<String, Object> headers = getTestHeaders("GET");
        Object response = importAllDatesProducer.requestBodyAndHeaders(null, headers);

        String jsonResult = new String(((java.io.InputStream) response).readAllBytes());

        @SuppressWarnings("unchecked")
        Map<String, String> allDates = objectMapper.readValue(jsonResult, Map.class);

        assertTrue(allDates.size() >= 3);
        assertTrue(allDates.containsKey("opp"));
        assertTrue(allDates.containsKey("flt"));
        assertTrue(allDates.containsKey("vyg"));

        assertEquals(dateTime1.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                allDates.get("opp"));
        assertEquals(dateTime2.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                allDates.get("flt"));
        assertEquals(dateTime3.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                allDates.get("vyg"));
    }

    private static Map<String, Object> getTestHeaders(String method) {
        return Map.of(
                Exchange.HTTP_METHOD, method
        );
    }

    private NetexImportEvent createEvent(String codespace, LocalDateTime importDateTime) {
        return NetexImportEvent.newBuilder()
                .setCodespace(codespace)
                .setImportDateTime(importDateTime.toString())
                .setImportKey(codespace + "_" + importDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .setPublishedDatasetURI("gs://test-bucket/" + codespace + ".zip")
                .setPublishedDatasetPublicLink("https://test.com/" + codespace + ".zip")
                .setOriginalDatasetURI("gs://test-bucket/original-" + codespace + ".zip")
                .setServiceJourneys(0)
                .setCommonFiles(0)
                .build();
    }
}
