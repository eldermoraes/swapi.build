package com.eldermoraes.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Plain copy of the counters, as exchanged between {@link RouteStats} and the
 * snapshot file ({@link SnapshotFile}). Only route templates, bucket starts and
 * counts: nothing about any client exists to be copied.
 *
 * @param countingSince when the oldest counts in here started being collected
 * @param hours         counts per hour bucket, keyed by the start of the hour (UTC)
 * @param days          counts per UTC day
 */
public record StatsData(Instant countingSince,
                        Map<Instant, Map<String, Long>> hours,
                        Map<LocalDate, Map<String, Long>> days) {
}
