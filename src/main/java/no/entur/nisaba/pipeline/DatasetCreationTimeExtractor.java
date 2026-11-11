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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Collects the creation time of every XML file in a NeTEx archive.
 * <p>
 * Replaces the Camel {@code split(new ZipSplitter())} route: the archive is read once, entries that are not
 * XML are skipped, and a file without a {@code created} attribute counts as created at the epoch.
 */
@Component
public class DatasetCreationTimeExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetCreationTimeExtractor.class);

    /**
     * Stands in for a file without a {@code created} attribute, so that the real creation times always sort
     * after it.
     */
    public static final LocalDateTime EPOCH = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);

    /**
     * @param archive the zip archive; consumed and closed.
     * @throws IllegalStateException  when the archive holds no XML file (a stream that is not a zip reads as empty),
     *                                or a file cannot be parsed.
     * @throws UncheckedIOException   when the archive is corrupt.
     */
    public DatasetCreationTimes extract(InputStream archive) {
        String prefix = ImportMdc.prefix();
        TreeSet<LocalDateTime> creationTimes = new TreeSet<>();
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String fileName = entry.getName();
                if (!fileName.endsWith(".xml")) {
                    LOGGER.info("{}Ignoring non-XML file {}", prefix, fileName);
                    continue;
                }
                Optional<String> created = CompositeFrameCreatedParser.parse(new EntryStream(zip));
                if (created.isEmpty() || created.get().isBlank()) {
                    LOGGER.warn("{}'created' attribute not found in file {}", prefix, fileName);
                    creationTimes.add(EPOCH);
                } else {
                    creationTimes.add(LocalDateTime.parse(created.get()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the NeTEx archive", e);
        }
        if (creationTimes.isEmpty()) {
            throw new IllegalStateException("No XML file found in the NeTEx archive");
        }
        return new DatasetCreationTimes(creationTimes);
    }

    /**
     * The XML parser closes its input when it reaches the end of a document, which would close the archive.
     */
    private static final class EntryStream extends FilterInputStream {
        private EntryStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // the archive stays open for the next entry
        }
    }
}
