package com.eldermoraes.stats;

import java.util.Optional;

/**
 * The windows {@code GET /api/stats} answers, each compared with the window of
 * the same length right before it.
 *
 * Which granularities a window accepts follows from what is retained:
 * hourly buckets for {@link RouteStats#RETAINED_HOURS} (two times seven days),
 * daily buckets for {@link RouteStats#RETAINED_DAYS} (two times thirty days).
 */
public enum StatsPeriod {
    LAST_24_HOURS("24h", 24, Granularity.HOUR, false),
    LAST_7_DAYS("7d", 7 * 24, Granularity.DAY, true),
    LAST_30_DAYS("30d", 30 * 24, Granularity.DAY, false);

    public enum Granularity {
        HOUR("hour"), DAY("day");

        private final String id;

        Granularity(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Granularity> parse(String id) {
            for (Granularity g : values()) {
                if (g.id.equals(id)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    private final String id;
    private final int hours;
    private final Granularity defaultGranularity;
    private final boolean hourlyAndDaily;

    StatsPeriod(String id, int hours, Granularity defaultGranularity, boolean hourlyAndDaily) {
        this.id = id;
        this.hours = hours;
        this.defaultGranularity = defaultGranularity;
        this.hourlyAndDaily = hourlyAndDaily;
    }

    public String id() {
        return id;
    }

    /** Length of the window in hours. */
    public int hours() {
        return hours;
    }

    /** Length of the window in days (1 for 24 h). */
    public int days() {
        return Math.max(1, hours / 24);
    }

    public Granularity defaultGranularity() {
        return defaultGranularity;
    }

    /** 24 h is hourly only, 30 d is daily only, 7 d accepts both. */
    public boolean accepts(Granularity granularity) {
        return granularity == defaultGranularity || hourlyAndDaily;
    }

    public static Optional<StatsPeriod> parse(String id) {
        for (StatsPeriod p : values()) {
            if (p.id.equals(id)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
