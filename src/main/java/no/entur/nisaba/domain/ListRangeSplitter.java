package no.entur.nisaba.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Split a list into n ranges of equal size.
 */
public class ListRangeSplitter {

    private final int rangeSize;

    /**
     * @param rangeSize the size of each range.
     */
    public ListRangeSplitter(int rangeSize) {
        this.rangeSize = rangeSize;
    }

    public List<Range> split(int nbItems) {

        ArrayList<Range> ranges = new ArrayList<>();
        int nbRanges = nbItems / rangeSize;

        for (int i = 0; i < nbRanges; i++) {
            ranges.add(new Range(1 + i * rangeSize, (i + 1) * rangeSize));
        }
        if (nbItems % rangeSize > 0) {
            ranges.add(new Range(1 + nbRanges * rangeSize, nbItems));
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
