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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest(controllers = {
    TimetableImportInfoController.class,
    OpenApiController.class
})
@ContextConfiguration(classes = {
    TimetableImportInfoController.class,
    OpenApiController.class,
    TimetableImportInfoControllerTest.TestConfig.class
})
class TimetableImportInfoControllerTest {

    @Configuration
    static class TestConfig implements WebMvcConfigurer {
        @Bean("importDatesMap")
        public Map<String, String> importDatesMap() {
            return new ConcurrentHashMap<>();
        }

        @Bean
        public ObjectMapper objectMapper() {
            ObjectMapper mapper = new ObjectMapper();
            JavaTimeModule javaTimeModule = new JavaTimeModule();
            mapper.registerModule(javaTimeModule);
            // Disable writing dates as timestamps - use ISO-8601 strings instead
            mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return mapper;
        }

        @Override
        public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            // Add custom converter for OffsetDateTime to text/plain
            converters.add(new AbstractHttpMessageConverter<OffsetDateTime>(MediaType.TEXT_PLAIN) {
                @Override
                protected boolean supports(Class<?> clazz) {
                    return OffsetDateTime.class.isAssignableFrom(clazz);
                }

                @Override
                protected OffsetDateTime readInternal(Class<? extends OffsetDateTime> clazz, HttpInputMessage inputMessage)
                        throws IOException, HttpMessageNotReadableException {
                    throw new UnsupportedOperationException("Reading not supported");
                }

                @Override
                protected void writeInternal(OffsetDateTime offsetDateTime, HttpOutputMessage outputMessage)
                        throws IOException, HttpMessageNotWritableException {
                    try (OutputStreamWriter writer = new OutputStreamWriter(outputMessage.getBody(), StandardCharsets.UTF_8)) {
                        writer.write(offsetDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                }
            });
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Map<String, String> importDatesMap;

    @BeforeEach
    void setup() {
        importDatesMap.clear();
        importDatesMap.put("avi", "2021-04-21T11:51:59");
        importDatesMap.put("rut", "2021-05-15T09:30:00");
        importDatesMap.put("atb", "2021-06-10T14:20:30");
    }

    @Test
    void testGetAllImportDates() throws Exception {
        mockMvc.perform(get("/timetable-import-info/import_date"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.avi").value("2021-04-21T11:51:59Z"))
            .andExpect(jsonPath("$.rut").value("2021-05-15T09:30:00Z"))
            .andExpect(jsonPath("$.atb").value("2021-06-10T14:20:30Z"));
    }

    @Test
    void testGetAllImportDates_EmptyMap() throws Exception {
        importDatesMap.clear();

        mockMvc.perform(get("/timetable-import-info/import_date"))
            .andExpect(status().isNotFound());
    }

    @Test
    void testGetImportDateByCodespace() throws Exception {
        mockMvc.perform(get("/timetable-import-info/import_date/avi"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("2021-04-21T11:51:59")));
    }

    @Test
    void testGetImportDateByCodespace_CaseInsensitive() throws Exception {
        mockMvc.perform(get("/timetable-import-info/import_date/AVI"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("2021-04-21T11:51:59")));
    }

    @Test
    void testGetImportDateByCodespace_NotFound() throws Exception {
        mockMvc.perform(get("/timetable-import-info/import_date/unknown"))
            .andExpect(status().isNotFound());
    }

    @Test
    void testGetOpenApiSpec() throws Exception {
        mockMvc.perform(get("/timetable-import-info/openapi.yaml"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/x-yaml"))
            .andExpect(content().string(containsString("openapi:")))
            .andExpect(content().string(containsString("Timetable Import Info")));
    }
}