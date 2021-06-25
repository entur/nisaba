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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ListRangeSplitterTest {

    private static final int RANGE_SIZE = 200;

    private final ListRangeSplitter listRangeSplitter = new ListRangeSplitter();

    @Test
    void testLessThanOneFullRange() {
        List<ListRangeSplitter.Range> split = listRangeSplitter.split(100, RANGE_SIZE);
        Assertions.assertEquals(1, split.size());
        Assertions.assertEquals(0, split.get(0).getLowerBound());
        Assertions.assertEquals(99, split.get(0).getUpperBound());
    }

    @Test
    void testMoreThanOneRange() {
        List<ListRangeSplitter.Range> split = listRangeSplitter.split(250, RANGE_SIZE);
        Assertions.assertEquals(2, split.size());
        Assertions.assertEquals(0, split.get(0).getLowerBound());
        Assertions.assertEquals(199, split.get(0).getUpperBound());
        Assertions.assertEquals(200, split.get(1).getLowerBound());
        Assertions.assertEquals(249, split.get(1).getUpperBound());
    }

    @Test
    void testNoPartialRange() {
        List<ListRangeSplitter.Range> split = listRangeSplitter.split(400, RANGE_SIZE);
        Assertions.assertEquals(2, split.size());
        Assertions.assertEquals(0, split.get(0).getLowerBound());
        Assertions.assertEquals(199, split.get(0).getUpperBound());
        Assertions.assertEquals(200, split.get(1).getLowerBound());
        Assertions.assertEquals(399, split.get(1).getUpperBound());
    }
}
