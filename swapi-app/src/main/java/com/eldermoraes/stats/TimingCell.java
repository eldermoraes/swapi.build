package com.eldermoraes.stats;

/**
 * The live accumulator behind one {@link TimingData}: one per route per hour
 * and per route per day.
 *
 * Its size is fixed whatever the traffic: five numbers and one {@code int} per
 * {@link LatencyHistogram} bucket, about 0.4 KB with the object headers.
 * {@code int} is enough for a bucket: it would take more than two billion
 * requests in one route, one day and one bucket to overflow it. Updates take
 * the cell's lock; at this site's traffic the lock is never contended long.
 */
final class TimingCell {

    private long count;
    private long sumMicros;
    private double sumSquares;
    private long minMicros = Long.MAX_VALUE;
    private long maxMicros;
    private final int[] buckets = new int[LatencyHistogram.BUCKETS];

    synchronized void record(long micros) {
        long t = Math.max(0, micros);
        count++;
        sumMicros += t;
        sumSquares += (double) t * t;
        minMicros = Math.min(minMicros, t);
        maxMicros = Math.max(maxMicros, t);
        buckets[LatencyHistogram.bucketOf(t)]++;
    }

    /** Adds restored numbers (the snapshot) to the live ones. */
    synchronized void add(TimingData data) {
        if (data.count() == 0) {
            return;
        }
        count += data.count();
        sumMicros += data.sumMicros();
        sumSquares += data.sumSquares();
        minMicros = Math.min(minMicros, data.minMicros());
        maxMicros = Math.max(maxMicros, data.maxMicros());
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = (int) Math.min(Integer.MAX_VALUE, buckets[i] + data.bucket(i));
        }
    }

    synchronized TimingData data() {
        if (count == 0) {
            return TimingData.EMPTY;
        }
        long[] b = new long[buckets.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = buckets[i];
        }
        return new TimingData(count, sumMicros, sumSquares, minMicros, maxMicros, b);
    }
}
