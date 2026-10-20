package com.eldermoraes.stats;

import java.util.Arrays;

/**
 * Server times of one route in one bucket (an hour, a day or a whole window),
 * as plain numbers that merge by addition.
 *
 * Count, sum and sum of squares give the mean and the standard deviation;
 * minimum and maximum are kept exactly; the {@link LatencyHistogram} counts
 * give the approximate percentiles. Merging two of these (hours into a day,
 * days into a window) is exact for everything but the percentiles, which stay
 * within the bucket error.
 *
 * Times are in microseconds. Nothing here is about a client.
 *
 * @param count      timed requests
 * @param sumMicros  sum of their times
 * @param sumSquares sum of the squares of their times (microseconds squared)
 * @param minMicros  fastest time (0 when {@code count} is 0)
 * @param maxMicros  slowest time (0 when {@code count} is 0)
 * @param buckets    requests per {@link LatencyHistogram} bucket, {@link LatencyHistogram#BUCKETS} long
 */
public record TimingData(long count, long sumMicros, double sumSquares, long minMicros, long maxMicros,
                         long[] buckets) {

    public static final TimingData EMPTY = new TimingData(0, 0, 0, 0, 0, new long[LatencyHistogram.BUCKETS]);

    public TimingData {
        if (buckets.length != LatencyHistogram.BUCKETS) {
            throw new IllegalArgumentException("expected " + LatencyHistogram.BUCKETS + " buckets");
        }
        buckets = buckets.clone();
    }

    /** One timed request. */
    public static TimingData of(long micros) {
        long[] b = new long[LatencyHistogram.BUCKETS];
        b[LatencyHistogram.bucketOf(micros)] = 1;
        return new TimingData(1, micros, (double) micros * micros, micros, micros, b);
    }

    @Override
    public long[] buckets() {
        return buckets.clone();
    }

    long bucket(int i) {
        return buckets[i];
    }

    public TimingData merge(TimingData other) {
        if (other.count == 0) {
            return this;
        }
        if (count == 0) {
            return other;
        }
        long[] b = new long[LatencyHistogram.BUCKETS];
        for (int i = 0; i < b.length; i++) {
            b[i] = buckets[i] + other.buckets[i];
        }
        return new TimingData(count + other.count, sumMicros + other.sumMicros, sumSquares + other.sumSquares,
                Math.min(minMicros, other.minMicros), Math.max(maxMicros, other.maxMicros), b);
    }

    public double meanMicros() {
        return count == 0 ? Double.NaN : (double) sumMicros / count;
    }

    /** Population standard deviation (divided by the count, not by count - 1). */
    public double stddevMicros() {
        if (count == 0) {
            return Double.NaN;
        }
        double mean = meanMicros();
        // Rounding can push the difference a hair below zero when all times are equal.
        return Math.sqrt(Math.max(0, sumSquares / count - mean * mean));
    }

    /**
     * Approximate percentile by nearest rank: the time of the request at rank
     * {@code ceil(p * count)}, estimated from its bucket and clamped to the exact
     * minimum and maximum (see {@link LatencyHistogram} for the error bound).
     *
     * @param p between 0 (exclusive) and 1 (inclusive), for example 0.95
     */
    public double percentileMicros(double p) {
        if (count == 0) {
            return Double.NaN;
        }
        long rank = Math.max(1, (long) Math.ceil(p * count));
        long seen = 0;
        int bucket = buckets.length - 1;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= rank) {
                bucket = i;
                break;
            }
        }
        double estimate = LatencyHistogram.estimate(bucket);
        return Math.min(maxMicros, Math.max(minMicros, estimate));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TimingData t && count == t.count && sumMicros == t.sumMicros
                && Double.compare(sumSquares, t.sumSquares) == 0 && minMicros == t.minMicros
                && maxMicros == t.maxMicros && Arrays.equals(buckets, t.buckets);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(count) + Arrays.hashCode(buckets);
    }

    @Override
    public String toString() {
        return "TimingData[count=" + count + ", sumMicros=" + sumMicros + ", sumSquares=" + sumSquares
                + ", minMicros=" + minMicros + ", maxMicros=" + maxMicros + ", buckets=" + Arrays.toString(buckets)
                + "]";
    }
}
