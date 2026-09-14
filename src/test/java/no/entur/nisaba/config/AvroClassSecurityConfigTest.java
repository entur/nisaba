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

import no.entur.nisaba.avro.NetexImportEvent;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the Avro generated classes can be resolved by name once
 * {@link AvroClassSecurityConfig} has installed the class security validator.
 * <p>
 * The Avro global validator is JVM wide static state shared by every test in the Surefire fork.
 * This test resets it to {@link ClassSecurityValidator#DEFAULT} around each test method so that it
 * neither depends on nor leaks the validator installed by the Spring based integration tests. It
 * never installs anything other than through {@link AvroClassSecurityConfig} itself.
 */
class AvroClassSecurityConfigTest {

    @BeforeEach
    @AfterEach
    void resetAvroGlobalValidator() {
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.DEFAULT);
    }

    /**
     * Documents the failure observed in production: with the Avro defaults the generated record is
     * rejected, which is what breaks the Kafka consumer.
     */
    @Test
    void avroRecordIsForbiddenWithAvroDefaults() {
        // a fresh SpecificData is used so that the assertion cannot be defeated by a successful
        // resolution already cached in the process wide SpecificData.get() singleton
        SecurityException exception = assertThrows(
                SecurityException.class,
                () -> new SpecificData().getClass(NetexImportEvent.getClassSchema())
        );
        assertTrue(exception.getMessage().contains(NetexImportEvent.class.getName()));
    }

    @Test
    void avroRecordIsResolvedByNameOnceTheValidatorIsInstalled() {
        new AvroClassSecurityConfig().trustNisabaAvroClasses();

        assertEquals(
                NetexImportEvent.class,
                new SpecificData().getClass(NetexImportEvent.getClassSchema())
        );
    }

    @Test
    void avroRecordCanBeRoundTripped() throws Exception {
        new AvroClassSecurityConfig().trustNisabaAvroClasses();

        NetexImportEvent event = NetexImportEvent.newBuilder()
                .setCodespace("opp")
                .setImportDateTime("2026-01-01T10:00:00")
                .setImportKey("opp_2026-01-01T10:00:00")
                .setPublishedDatasetURI("gs://test-bucket/opp.zip")
                .setPublishedDatasetPublicLink("https://test.com/opp.zip")
                .setOriginalDatasetURI("gs://test-bucket/original-opp.zip")
                .setServiceJourneys(0)
                .setCommonFiles(0)
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        // the single argument constructor resolves the record class by name, unlike the two
        // argument one used by the Confluent serializer, so this exercises the validator as well
        new SpecificDatumWriter<NetexImportEvent>(NetexImportEvent.getClassSchema()).write(event, encoder);
        encoder.flush();

        NetexImportEvent decoded =
                new SpecificDatumReader<NetexImportEvent>(NetexImportEvent.getClassSchema())
                        .read(null, DecoderFactory.get().binaryDecoder(out.toByteArray(), null));

        assertEquals("opp", decoded.getCodespace().toString());
        assertEquals("2026-01-01T10:00:00", decoded.getImportDateTime().toString());
    }

    /**
     * Guards against installing the validator without composing the Avro defaults back in.
     * The generated getters return {@link CharSequence}, so losing the defaults would break
     * deserialization in a different way.
     */
    @Test
    void avroDefaultTrustedClassesSurviveComposition() {
        new AvroClassSecurityConfig().trustNisabaAvroClasses();

        assertDoesNotThrow(() -> ClassSecurityValidator.validate(CharSequence.class));
        assertDoesNotThrow(() -> ClassSecurityValidator.validate(String.class));
        assertDoesNotThrow(() -> ClassSecurityValidator.validate(Integer.class));
    }

    /**
     * The allowlist must stay narrow: only the generated record is added to the Avro defaults.
     */
    @Test
    void unrelatedClassesRemainForbidden() {
        new AvroClassSecurityConfig().trustNisabaAvroClasses();

        assertThrows(SecurityException.class, () -> ClassSecurityValidator.validate(java.io.File.class));
    }
}
