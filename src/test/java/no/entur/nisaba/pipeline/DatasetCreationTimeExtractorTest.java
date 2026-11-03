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

package no.entur.nisaba.pipeline;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetCreationTimeExtractorTest {

    private final DatasetCreationTimeExtractor extractor = new DatasetCreationTimeExtractor();

    private static InputStream resource(String name) {
        return DatasetCreationTimeExtractorTest.class.getResourceAsStream("/no/entur/nisaba/netex/import/" + name);
    }

    private static InputStream zip(String... nameAndContent) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameAndContent[i]));
                zip.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    @Test
    void collectsEveryXmlFileAndSkipsTheRest() {
        DatasetCreationTimes times = extractor.extract(resource("rb_avi-aggregated-netex.zip"));
        assertEquals(List.of(
                LocalDateTime.parse("2021-04-01T09:09:45.409"),
                LocalDateTime.parse("2021-04-05T09:09:45.409"),
                LocalDateTime.parse("2021-04-13T09:09:45.409")), List.copyOf(times.all()));
        assertEquals(LocalDateTime.parse("2021-04-13T09:09:45.409"), times.latest());
    }

    @Test
    void fileWithoutCreatedAttributeCountsAsEpoch() {
        DatasetCreationTimes times = extractor.extract(resource("rb_test-missing-created-aggregated-netex.zip"));
        assertEquals(List.of(DatasetCreationTimeExtractor.EPOCH, LocalDateTime.parse("2021-04-13T09:09:45.409")), List.copyOf(times.all()));
        assertEquals(LocalDateTime.parse("2021-04-13T09:09:45.409"), times.latest());
        assertEquals(LocalDateTime.parse("1970-01-01T00:00:00"), DatasetCreationTimeExtractor.EPOCH);
    }

    @Test
    void archiveWithoutXmlFails() throws Exception {
        assertThrows(IllegalStateException.class, () -> extractor.extract(zip("readme.txt", "hello")));
        assertThrows(IllegalStateException.class, () -> extractor.extract(zip()));
    }

    @Test
    void directoriesAreSkippedAndEntryNamesWithFoldersAreAccepted() throws Exception {
        DatasetCreationTimes times = extractor.extract(zip("folder/", "",
                "folder/line.xml", "<PublicationDelivery xmlns=\"http://www.netex.org.uk/netex\"><dataObjects><CompositeFrame created=\"2021-01-01T00:00:00\"/></dataObjects></PublicationDelivery>"));
        assertEquals(LocalDateTime.parse("2021-01-01T00:00:00"), times.latest());
    }

    @Test
    void garbageIsNotAnArchive() {
        assertThrows(IllegalStateException.class, () -> extractor.extract(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void unparsableCreatedValueFails() throws Exception {
        assertThrows(java.time.format.DateTimeParseException.class, () -> extractor.extract(zip(
                "a.xml", "<PublicationDelivery xmlns=\"http://www.netex.org.uk/netex\"><dataObjects><CompositeFrame created=\"yesterday\"/></dataObjects></PublicationDelivery>")));
    }
}
