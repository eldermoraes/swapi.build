package com.eldermoraes.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Plain copy of the counters, as exchanged between {@link RouteStats} and the
 * snapshot file ({@link SnapshotFile}). Only route templates, bucket starts,
 * counts and server times: nothing about any client exists to be copied.
 *
 * @param countingSince when the oldest counts in here started being collected
 * @param hours         counts per hour bucket, keyed by the start of the hour (UTC)
 * @param days          counts per UTC day
 * @param hourTimes     server times per hour bucket and route (2.8.0+; empty in older snapshots)
 * @param dayTimes      server times per UTC day and route (2.8.0+; empty in older snapshots)
 */
public record StatsData(Instant countingSince,
                        Map<Instant, Map<String, Long>> hours,
                        Map<LocalDate, Map<String, Long>> days,
                        Map<Instant, Map<String, TimingData>> hourTimes,
                        Map<LocalDate, Map<String, TimingData>> dayTimes) {

    /** Counts only, as 2.7.0 wrote them. */
    public StatsData(Instant countingSince, Map<Instant, Map<String, Long>> hours,
                     Map<LocalDate, Map<String, Long>> days) {
        this(countingSince, hours, days, Map.of(), Map.of());
    }
}
