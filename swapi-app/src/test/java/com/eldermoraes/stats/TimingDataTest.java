package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingDataTest {

    private static final double[] PERCENTILES = {0.50, 0.95, 0.99};

    private static TimingData of(long... micros) {
        TimingCell cell = new TimingCell();
        for (long m : micros) {
            cell.record(m);
        }
        return cell.data();
    }

    /** Nearest rank on the sorted values: the reference the estimate is checked against. */
    private static long exactPercentile(long[] sorted, double p) {
        int rank = (int) Math.max(1, Math.ceil(p * sorted.length));
        return sorted[rank - 1];
    }

    private static void assertWithinBucketError(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        TimingData t = of(values);
        for (double p : PERCENTILES) {
            double exact = exactPercentile(sorted, p);
            double estimate = t.percentileMicros(p);
            if (exact < LatencyHistogram.FIRST_BOUND_MICROS) {
                assertTrue(Math.abs(estimate - exact) <= LatencyHistogram.FIRST_BOUND_MICROS / 2.0,
                        "p" + p + ": " + estimate + " vs " + exact);
            } else {
                double error = Math.abs(estimate - exact) / exact;
                assertTrue(error <= LatencyHistogram.MAX_RELATIVE_ERROR + 1e-9,
                        "p" + p + ": estimate " + estimate + " vs exact " + exact + ", error " + error);
            }
        }
    }

    @Test
    void meanMinMaxAndStandardDeviationAreExact() {
        TimingData t = of(1000, 2000, 3000, 4000, 5000);

        assertEquals(5, t.count());
        assertEquals(3000.0, t.meanMicros(), 1e-9);
        assertEquals(1000, t.minMicros());
        assertEquals(5000, t.maxMicros());
        // Population standard deviation of 1..5 ms: sqrt(2) ms.
        assertEquals(Math.sqrt(2) * 1000, t.stddevMicros(), 1e-6);
    }

    @Test
    void equalTimesHaveNoSpreadAndExactPercentiles() {
        TimingData t = of(3000, 3000, 3000, 3000);

        assertEquals(0.0, t.stddevMicros(), 1e-9);
        for (double p : PERCENTILES) {
            assertEquals(3000.0, t.percentileMicros(p), 1e-9, "clamped to min = max");
        }
    }

    @Test
    void emptyHasNoStatistics() {
        assertTrue(Double.isNaN(TimingData.EMPTY.meanMicros()));
        assertTrue(Double.isNaN(TimingData.EMPTY.stddevMicros()));
        assertTrue(Double.isNaN(TimingData.EMPTY.percentileMicros(0.5)));
    }

    @Test
    void uniformDistributionPercentilesStayWithinTheBucketError() {
        long[] values = new long[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i + 1) * 1000L;                  // 1 ms .. 1000 ms
        }
        assertWithinBucketError(values);
        TimingData t = of(values);
        assertEquals(500_000, t.percentileMicros(0.50), 500_000 * LatencyHistogram.MAX_RELATIVE_ERROR);
        assertEquals(950_000, t.percentileMicros(0.95), 950_000 * LatencyHistogram.MAX_RELATIVE_ERROR);
        assertEquals(990_000, t.percentileMicros(0.99), 990_000 * LatencyHistogram.MAX_RELATIVE_ERROR);
    }

    @Test
    void bimodalDistributionPutsTheTailInTheSlowMode() {
        long[] values = new long[1000];
        Arrays.fill(values, 0, 900, 2_000);               // 90 % at 2 ms
        Arrays.fill(values, 900, 1000, 200_000);          // 10 % at 200 ms
        TimingData t = of(values);

        assertWithinBucketError(values);
        assertEquals(2_000, t.percentileMicros(0.50), 2_000 * LatencyHistogram.MAX_RELATIVE_ERROR);
        assertEquals(200_000, t.percentileMicros(0.95), 200_000 * LatencyHistogram.MAX_RELATIVE_ERROR);
        assertTrue(t.percentileMicros(0.99) <= 200_000, "never above the exact maximum");
        assertEquals(0.9 * 2_000 + 0.1 * 200_000, t.meanMicros(), 1e-6);
    }

    @Test
    void exponentialAndLogNormalDistributionsStayWithinTheBucketError() {
        int n = 20_000;
        // Exponential with a 10 ms mean, by its quantile function at evenly spaced points.
        DoubleUnaryOperator exponential = u -> -Math.log(1 - u) * 10_000;
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = Math.round(exponential.applyAsDouble((i + 0.5) / n));
        }
        assertWithinBucketError(values);

        // Log-normal around 5 ms with a long tail, seeded so the test is repeatable.
        Random random = new Random(42);
        for (int i = 0; i < n; i++) {
            values[i] = Math.round(Math.exp(Math.log(5_000) + 1.2 * random.nextGaussian()));
        }
        assertWithinBucketError(values);
    }

    @Test
    void timesBelowTheFirstBoundAreOffByAtMostHalfOfIt() {
        long[] values = new long[100];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 50;                           // 0 .. 49 microseconds
        }
        assertWithinBucketError(values);
    }

    @Test
    void bucketsGrowByTheFourthRootOfTwo() {
        assertEquals(0, LatencyHistogram.bucketOf(0));
        assertEquals(0, LatencyHistogram.bucketOf(49));
        assertEquals(1, LatencyHistogram.bucketOf(50));
        assertEquals(0, LatencyHistogram.bucketOf(-5), "a negative time counts as zero");
        assertEquals(LatencyHistogram.BUCKETS - 1, LatencyHistogram.bucketOf(Long.MAX_VALUE));
        for (int i = 1; i < LatencyHistogram.BUCKETS - 1; i++) {
            assertEquals(Math.pow(2, 0.25), LatencyHistogram.upperBound(i) / LatencyHistogram.lowerBound(i), 1e-9);
            assertEquals(LatencyHistogram.upperBound(i - 1), LatencyHistogram.lowerBound(i), 0);
        }
        // The last bounded bucket ends at about 74.1 s.
        assertEquals(74.1, LatencyHistogram.upperBound(LatencyHistogram.BUCKETS - 2) / 1e6, 0.05);
        assertEquals(0.0905, LatencyHistogram.MAX_RELATIVE_ERROR, 0.0005);
    }

    @Test
    void mergingEqualsRecordingEverythingInOnePlace() {
        TimingData a = of(800, 1_200, 50_000);
        TimingData b = of(3, 9_000_000);

        assertEquals(of(800, 1_200, 50_000, 3, 9_000_000), a.merge(b));
        assertEquals(a, a.merge(TimingData.EMPTY));
        assertEquals(b, TimingData.EMPTY.merge(b));
    }

    @Test
    void restoredNumbersAddToALiveCell() {
        TimingCell cell = new TimingCell();
        cell.record(1_000);
        cell.add(of(2_000, 4_000));

        assertEquals(of(1_000, 2_000, 4_000), cell.data());
    }
}
