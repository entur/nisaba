package no.entur.nisaba.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ListRangeSplitterTest {

    private final ListRangeSplitter listRangeSplitter = new ListRangeSplitter(200);

    @Test
    void testLessThanOneFullRange() {
        List<ListRangeSplitter.Range> split = listRangeSplitter.split(100);
        Assertions.assertEquals(1, split.size());
        Assertions.assertEquals(1, split.get(0).getLowerBound());
        Assertions.assertEquals(100, split.get(0).getUpperBound());
    }

    @Test
    void testMoreThanOneRange() {
        List<ListRangeSplitter.Range> split = listRangeSplitter.split(250);
        Assertions.assertEquals(2, split.size());
        Assertions.assertEquals(1, split.get(0).getLowerBound());
        Assertions.assertEquals(200, split.get(0).getUpperBound());
        Assertions.assertEquals(201, split.get(1).getLowerBound());
        Assertions.assertEquals(250, split.get(1).getUpperBound());
    }

    @Test
    void testNoPartialRange() {
        List<ListRangeSplitter.Range> split = listRangeSplitter.split(400);
        Assertions.assertEquals(2, split.size());
        Assertions.assertEquals(1, split.get(0).getLowerBound());
        Assertions.assertEquals(200, split.get(0).getUpperBound());
        Assertions.assertEquals(201, split.get(1).getLowerBound());
        Assertions.assertEquals(400, split.get(1).getUpperBound());
    }
}
