package com.eldermoraes.stats;

import java.util.Arrays;

/**
 * The fixed buckets that server times are counted in, for approximate
 * percentiles.
 *
 * Bucket 0 holds everything below {@link #FIRST_BOUND_MICROS} (0.05 ms). From
 * there, each bucket is {@code 2^(1/4)} (about 1.19) times wider than the one
 * before: four buckets per doubling, up to bucket 82, which ends at
 * {@code 0.05 ms * 2^20.5}, about 74.1 seconds. Bucket 83 holds everything
 * slower than that.
 *
 * A percentile is estimated as the geometric middle of the bucket its rank
 * falls in, then clamped to the exact minimum and maximum. The error is
 * bounded by the bucket width:
 * <ul>
 *   <li>from 0.05 ms to 74.1 s: at most {@link #MAX_RELATIVE_ERROR}
 *       ({@code 2^(1/8) - 1}, about 9.05 %) of the true value;</li>
 *   <li>below 0.05 ms: at most 0.025 ms (the estimate is the middle of 0..0.05 ms);</li>
 *   <li>above 74.1 s: reported as 74.1 s (only a hung request lands there;
 *       the exact maximum still shows it).</li>
 * </ul>
 * Mean, minimum, maximum and standard deviation are exact; only the
 * percentiles go through the buckets.
 */
public final class LatencyHistogram {

    /** Upper bound of bucket 0, in microseconds (0.05 ms). */
    public static final long FIRST_BOUND_MICROS = 50;

    /** Buckets per doubling of the time. */
    public static final int STEPS_PER_DOUBLING = 4;

    /** Number of buckets, the overflow bucket included. */
    public static final int BUCKETS = 84;

    /** Worst relative error of a percentile between 0.05 ms and 74.1 s. */
    public static final double MAX_RELATIVE_ERROR = Math.pow(2, 1.0 / (2 * STEPS_PER_DOUBLING)) - 1;

    /** {@code BOUNDS[i]} is the exclusive upper bound of bucket {@code i}, for i below 83, in microseconds. */
    private static final double[] BOUNDS = new double[BUCKETS - 1];

    static {
        for (int i = 0; i < BOUNDS.length; i++) {
            BOUNDS[i] = FIRST_BOUND_MICROS * Math.pow(2, (double) i / STEPS_PER_DOUBLING);
        }
    }

    private LatencyHistogram() {
    }

    /** The bucket a time of {@code micros} microseconds falls in. */
    public static int bucketOf(long micros) {
        int i = Arrays.binarySearch(BOUNDS, (double) Math.max(0, micros));
        // An exact hit on a bound belongs to the bucket that starts there.
        return i >= 0 ? i + 1 : -i - 1;
    }

    /** Inclusive lower bound of bucket {@code i}, in microseconds. */
    public static double lowerBound(int i) {
        return i == 0 ? 0 : BOUNDS[i - 1];
    }

    /** Exclusive upper bound of bucket {@code i}, in microseconds; infinite for the overflow bucket. */
    public static double upperBound(int i) {
        return i < BOUNDS.length ? BOUNDS[i] : Double.POSITIVE_INFINITY;
    }

    /** The value a percentile landing in bucket {@code i} is reported as, in microseconds. */
    static double estimate(int i) {
        if (i == 0) {
            return FIRST_BOUND_MICROS / 2.0;
        }
        if (i >= BOUNDS.length) {
            return BOUNDS[BOUNDS.length - 1];
        }
        return Math.sqrt(lowerBound(i) * upperBound(i));
    }
}
