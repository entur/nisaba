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

import java.time.LocalDateTime;
import java.util.SortedSet;

/**
 * The {@code created} timestamps of the CompositeFrames of a NeTEx archive, one per XML file.
 *
 * @param all every creation time, in ascending order; never empty.
 */
public record DatasetCreationTimes(SortedSet<LocalDateTime> all) {

    public DatasetCreationTimes {
        if (all == null || all.isEmpty()) {
            throw new IllegalArgumentException("at least one creation time is required");
        }
    }

    /**
     * The most recent creation time, which identifies the dataset import. A dataset that merges a Chouette
     * and an Uttu export carries two creation times; the import is identified by the later one.
     */
    public LocalDateTime latest() {
        return all.last();
    }
}
