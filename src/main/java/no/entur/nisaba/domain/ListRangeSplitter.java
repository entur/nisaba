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

package no.entur.nisaba.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Split a list into n ranges of equal size.
 */
public class ListRangeSplitter {


    public List<Range> split(int nbItems, int rangeSize) {

        ArrayList<Range> ranges = new ArrayList<>();
        int nbRanges = nbItems / rangeSize;

        for (int i = 0; i < nbRanges; i++) {
            ranges.add(new Range(i * rangeSize, (i + 1) * rangeSize - 1));
        }
        if (nbItems % rangeSize > 0) {
            ranges.add(new Range(nbRanges * rangeSize, nbItems - 1));
        }

        return ranges;
    }


    /**
     * Define a range represented by its lower bound and upper bound indexes.
     * Indexes start at 1.
     */
    public static class Range {
        private final int lowerBound;
        private final int upperBound;

        private Range(int lowerBound, int upperBound) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
        }

        public int getLowerBound() {
            return lowerBound;
        }

        public int getUpperBound() {
            return upperBound;
        }


    }
}
