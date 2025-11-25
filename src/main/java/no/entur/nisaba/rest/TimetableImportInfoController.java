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

package no.entur.nisaba.rest;

import no.entur.nisaba.rest.api.TimetableImportInfoApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for timetable import information.
 * Implements the OpenAPI-generated interface.
 *
 * This controller reads from the shared importDatesMap that is populated
 * by the Kafka consumer route (ImportDateMapUpdater).
 */
@RestController
@RequestMapping
public class TimetableImportInfoController implements TimetableImportInfoApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimetableImportInfoController.class);

    private final Map<String, String> importDates;

    public TimetableImportInfoController(@Qualifier("importDatesMap") Map<String, String> importDates) {
        this.importDates = importDates;
    }

    @Override
    public ResponseEntity<Map<String, OffsetDateTime>> getAllImportDates() {
        LOGGER.info("Received request to get all import dates");

        if (importDates.isEmpty()) {
            LOGGER.warn("No import dates available");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        // Convert String dates to OffsetDateTime with proper serialization format
        Map<String, OffsetDateTime> convertedDates = importDates.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> LocalDateTime.parse(entry.getValue(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atOffset(ZoneOffset.UTC)
            ));

        LOGGER.info("Returning {} import dates", convertedDates.size());
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(convertedDates);
    }

    @Override
    public ResponseEntity<OffsetDateTime> getImportDateByCodespace(String codespace) {
        LOGGER.info("Received request to get import date for codespace '{}'", codespace);

        String importDate = importDates.get(codespace.toLowerCase());

        if (importDate != null) {
            OffsetDateTime dateTime = LocalDateTime.parse(importDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atOffset(ZoneOffset.UTC);
            LOGGER.info("Found import date for codespace '{}': {}", codespace, importDate);
            // Return as text/plain with formatted string
            return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(dateTime);
        } else {
            LOGGER.warn("Codespace '{}' not found", codespace);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }
}