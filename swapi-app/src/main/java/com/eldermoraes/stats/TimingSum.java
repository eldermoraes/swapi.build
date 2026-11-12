package com.eldermoraes.stats;

import java.util.Arrays;

/**
 * A running total of server times, for building one view: the times of a
 * route over a window, of a group, or of one hour or day over all routes.
 *
 * It exists so that a view adds each live {@link TimingCell} straight into
 * its totals. Going through one {@link TimingData} per cell and per merge
 * copied every 84-bucket histogram six times, 32.6 MB for one 7-day hourly
 * view with every route busy; a view now allocates one of these per route and
 * per group, and reuses one for every bucket of the series.
 *
 * Not thread-safe: it belongs to the one build that created it. Adding gives
 * the same numbers as {@link TimingData#merge}.
 */
final class TimingSum {

    private long count;
    private long sumMicros;
    private double sumSquares;
    private long minMicros = Long.MAX_VALUE;
    private long maxMicros;
    private final long[] buckets = new long[LatencyHistogram.BUCKETS];

    long count() {
        return count;
    }

    /** Back to no requests, keeping the array. */
    void clear() {
        count = 0;
        sumMicros = 0;
        sumSquares = 0;
        minMicros = Long.MAX_VALUE;
        maxMicros = 0;
        Arrays.fill(buckets, 0);
    }

    void add(TimingSum other) {
        if (other.count == 0) {
            return;
        }
        count += other.count;
        sumMicros += other.sumMicros;
        sumSquares += other.sumSquares;
        minMicros = Math.min(minMicros, other.minMicros);
        maxMicros = Math.max(maxMicros, other.maxMicros);
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] += other.buckets[i];
        }
    }

    /** The numbers of a {@link TimingCell}, which calls this holding its lock. */
    void add(long cellCount, long cellSumMicros, double cellSumSquares, long cellMinMicros, long cellMaxMicros,
             int[] cellBuckets) {
        if (cellCount == 0) {
            return;
        }
        count += cellCount;
        sumMicros += cellSumMicros;
        sumSquares += cellSumSquares;
        minMicros = Math.min(minMicros, cellMinMicros);
        maxMicros = Math.max(maxMicros, cellMaxMicros);
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] += cellBuckets[i];
        }
    }

    /** The total so far as a value; later additions do not reach it. */
    TimingData data() {
        return count == 0
                ? TimingData.EMPTY
                : new TimingData(count, sumMicros, sumSquares, minMicros, maxMicros, buckets);
    }
}
