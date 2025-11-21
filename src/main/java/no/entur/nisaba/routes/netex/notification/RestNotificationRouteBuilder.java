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
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.model.rest.RestParamType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create and update an in-memory map of the latest NeTEx import dates and expose it as a REST service.
 */
@Component
public class RestNotificationRouteBuilder extends BaseRouteBuilder {


    private static final String PLAIN = "text/plain";
    private static final String JSON = "application/json";
    private static final String SWAGGER_DATA_TYPE_STRING = "string";
    private static final String CODESPACE_PARAM = "codespace";

    private final Map<String, String> importDates = new ConcurrentHashMap<>(50);

    @Value("${server.port:8080}")
    private String port;

    @Value("${server.host:0.0.0.0}")
    private String host;

    @Override
    public void configure() throws Exception {
        super.configure();

        onException(BadRequestException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant(PLAIN))
                .transform(exceptionMessage());

        onException(NotFoundException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .setHeader(Exchange.CONTENT_TYPE, constant(PLAIN))
                .transform(exceptionMessage());


        restConfiguration()
                .component("platform-http")
                .contextPath("/services")
                .bindingMode(RestBindingMode.off)
                .apiContextPath("/swagger.json")

                .apiProperty("api.title", "Timetable Import Info API")
                .apiProperty("api.version", "1.0")
                .apiProperty("api.description", "Provide for each codespace the date of the latest successful NeTEx dataset import")

                .apiProperty("schemes", "https")
                .apiProperty("host", "api.entur.io/timetable-public/v1/timetable-import-info");

        String commonApiDocEndpoint = "http:" + host + ":" + port + "/services/swagger.json?bridgeEndpoint=true";

        rest("/timetable-import-info")

                .get("import_date")
                .description("Return the date of the latest NeTEx import for all codespaces")
                .consumes(PLAIN)
                .produces(JSON)
                .responseMessage().code(200).endResponseMessage()
                .responseMessage().code(500).message("Internal error").endResponseMessage()
                .to("direct:importAllDates")

                .get("import_date/{" + CODESPACE_PARAM + '}')
                .description("Return the date of the latest NeTEx import for a given codespace")
                .param().name(CODESPACE_PARAM)
                .type(RestParamType.path)
                .description("Codespace of the data provider")
                .dataType(SWAGGER_DATA_TYPE_STRING)
                .required(true)
                .endParam()
                .consumes(PLAIN)
                .produces(PLAIN)
                .responseMessage().code(200).endResponseMessage()
                .responseMessage().code(404).message("Unknown codespace").endResponseMessage()
                .responseMessage().code(500).message("Internal error").endResponseMessage()
                .to("direct:importDate")

                .get("/swagger.json")
                .apiDocs(false)
                .bindingMode(RestBindingMode.off)
                .to(commonApiDocEndpoint);


        from("direct:adminRouteAuthorizeGet")
                .throwException(new NotFoundException())
                .routeId("admin-route-authorize-get");

        from("direct:adminRouteAuthorizePost")
                .throwException(new NotFoundException())
                .routeId("admin-route-authorize-post");

        from("direct:adminRouteAuthorizePut")
                .throwException(new NotFoundException())
                .routeId("admin-route-authorize-put");

        from("direct:adminRouteAuthorizeDelete")
                .throwException(new NotFoundException())
                .routeId("admin-route-authorize-delete");

        from("direct:importAllDates")
                .setBody(e -> importDates)
                .marshal().json(JsonLibrary.Jackson)
                .routeId("import-all-dates");

        from("direct:importDate")
                .process(exchange -> {
                    String codespace = exchange.getIn().getHeader(CODESPACE_PARAM, String.class);
                    String importDate = importDates.get(codespace.toLowerCase());
                    if (importDate != null) {
                        exchange.getIn().setBody(importDate);
                    } else {
                        throw new NotFoundException("Codespace not found");
                    }
                })
                .routeId("import-date");

        from("kafka:{{nisaba.kafka.topic.event}}?clientId=nisaba-event-reader&headerFilterStrategy=#nisabaKafkaHeaderFilterStrategy&valueDeserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer&specificAvroReader=true&seekTo=beginning&autoOffsetReset=earliest&offsetRepository=#nisabaEventReaderOffsetRepo")
                .log(LoggingLevel.INFO, correlation() + "Received notification event from ${properties:nisaba.kafka.topic.event}")
                .to("direct:updateImportDateMap")
                .routeId("from-kafka-topic-event");

        from("direct:updateImportDateMap")
                .process(exchange -> {
                            NetexImportEvent netexImportEvent = exchange.getIn().getBody(NetexImportEvent.class);
                            String importDate = netexImportEvent.getImportDateTime().toString();
                            LocalDateTime parsedDateTime = LocalDateTime.parse(importDate);
                            String truncatedImportDate = parsedDateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                            importDates.put(netexImportEvent.getCodespace().toString(), truncatedImportDate);
                            log.debug("Registered import dates : {}", importDates);
                        }
                )
                .routeId("update-import-date-map");


    }

}
