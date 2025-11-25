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

import no.entur.nisaba.avro.NetexImportEvent;
import no.entur.nisaba.routes.BaseRouteBuilder;
import org.apache.camel.LoggingLevel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Camel route that consumes Kafka events and updates the in-memory import dates map.
 * The REST API is now handled by TimetableImportInfoController.
 */
@Component
public class ImportDateMapUpdater extends BaseRouteBuilder {

    private final Map<String, String> importDates;

    public ImportDateMapUpdater(@Qualifier("importDatesMap") Map<String, String> importDates) {
        this.importDates = importDates;
    }

    @Override
    public void configure() throws Exception {
        super.configure();

        // Kafka consumer route - consumes NeTEx import events
        from("kafka:{{nisaba.kafka.topic.event}}" +
             "?clientId=nisaba-event-reader" +
             "&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy" +
             "&valueDeserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer" +
             "&specificAvroReader=true" +
             "&seekTo=beginning" +
             "&autoOffsetReset=earliest" +
             "&offsetRepository=#nisabaEventReaderOffsetRepo")
            .log(LoggingLevel.INFO, correlation() +
                "Received notification event from ${properties:nisaba.kafka.topic.event}")
            .to("direct:updateImportDateMap")
            .routeId("from-kafka-topic-event");

        // Map update route - processes the event and updates the shared map
        from("direct:updateImportDateMap")
            .process(exchange -> {
                NetexImportEvent netexImportEvent = exchange.getIn().getBody(NetexImportEvent.class);
                String importDate = netexImportEvent.getImportDateTime().toString();
                LocalDateTime parsedDateTime = LocalDateTime.parse(importDate);
                String truncatedImportDate = parsedDateTime
                    .truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                importDates.put(netexImportEvent.getCodespace().toString(), truncatedImportDate);
                log.debug("Registered import dates: {}", importDates);
            })
            .routeId("update-import-date-map");
    }
}