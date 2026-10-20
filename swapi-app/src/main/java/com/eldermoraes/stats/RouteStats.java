package com.eldermoraes.stats;

import com.eldermoraes.stats.StatsPeriod.Granularity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Request counters and server times per route template, per UTC hour and per
 * UTC day.
 *
 * Nothing about the client is ever passed in: the only inputs are the route
 * template produced by {@link RouteNormalizer} and, for the times, how long the
 * server took to answer. No IP, no User-Agent, no query string, no id.
 *
 * Each request lands in two buckets, its hour and its day, once for the count
 * ({@link #record}) and once for its time ({@link #recordDuration}). Hours are
 * kept for {@link #RETAINED_HOURS} (the 7-day hourly view and the 7 days before
 * it, for the comparison), days for {@link #RETAINED_DAYS} (the 30-day view and
 * the 30 days before it). Memory is bounded twice: the route set is closed (45
 * templates), and older buckets are dropped. With every route busy every hour
 * that is 45 x (336 + 60) = 17,820 counters and as many {@link TimingCell}s of
 * about 0.5 KB each with their map entry, so under 9 MB in the worst case; in
 * practice a small fraction of that, since most routes are idle most hours.
 *
 * The counters live in memory. {@link StatsPersistence} copies them to a file
 * when the host gives the app a writable state directory, so a restart (a
 * deploy, for one) does not reset them; without it they restart from zero.
 */
@Singleton
public class RouteStats {

    /** Hourly buckets kept: 7 days for the hourly view of 7 days, and 7 more before them. */
    public static final int RETAINED_HOURS = 2 * 7 * 24;

    /** Daily buckets kept: 30 days for the 30-day view, and 30 more before them. */
    public static final int RETAINED_DAYS = 2 * 30;

    /** Rough size of one live {@link TimingCell} with its map entry, for the memory estimate. */
    static final int TIMING_CELL_BYTES = 512;

    private static final long HOUR_SECONDS = 3600;

    private final Map<Long, Map<String, LongAdder>> hours = new ConcurrentHashMap<>();
    private final Map<LocalDate, Map<String, LongAdder>> days = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, TimingCell>> hourTimes = new ConcurrentHashMap<>();
    private final Map<LocalDate, Map<String, TimingCell>> dayTimes = new ConcurrentHashMap<>();
    private final LongAdder changes = new LongAdder();
    private final Clock clock;
    private final AtomicReference<Instant> countingSince;

    public RouteStats() {
        this(Clock.systemUTC());
    }

    RouteStats(Clock clock) {
        this.clock = clock;
        this.countingSince = new AtomicReference<>(clock.instant());
    }

    /** Counts one request on a route template from {@link RouteNormalizer}. */
    public void record(String route) {
        Instant now = clock.instant();
        long hour = epochHour(now);
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        bucket(hours, hour, () -> pruneHours(hour)).computeIfAbsent(route, r -> new LongAdder()).increment();
        bucket(days, day, () -> pruneDays(day)).computeIfAbsent(route, r -> new LongAdder()).increment();
        changes.increment();
    }

    /**
     * Adds the server time of one request on a route template from
     * {@link RouteNormalizer}: from when the first route filter saw the request
     * to when the response was fully written ({@link RouteCountingFilter}).
     * It lands in the hour and the day the response ended in.
     *
     * @param nanos elapsed time in nanoseconds (kept in whole microseconds)
     */
    public void recordDuration(String route, long nanos) {
        long micros = Math.max(0, nanos / 1000);
        Instant now = clock.instant();
        long hour = epochHour(now);
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        bucket(hourTimes, hour, () -> pruneHours(hour)).computeIfAbsent(route, r -> new TimingCell()).record(micros);
        bucket(dayTimes, day, () -> pruneDays(day)).computeIfAbsent(route, r -> new TimingCell()).record(micros);
        changes.increment();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Counts of one day, sorted by count (descending), then by route. */
    public Map<String, Long> day(LocalDate date) {
        return sorted(counts(days.get(date)));
    }

    /** Counts of the hour that starts at {@code hourStart} (UTC). */
    public Map<String, Long> hour(Instant hourStart) {
        return sorted(counts(hours.get(epochHour(hourStart))));
    }

    /** Server times of one route on one day. */
    TimingData dayTime(LocalDate date, String route) {
        return times(dayTimes.get(date)).getOrDefault(route, TimingData.EMPTY);
    }

    /** Server times of one route in the hour that starts at {@code hourStart} (UTC). */
    TimingData hourTime(Instant hourStart, String route) {
        return times(hourTimes.get(epochHour(hourStart))).getOrDefault(route, TimingData.EMPTY);
    }

    /** Live timing cells (route x hour plus route x day). */
    int timingCells() {
        int n = 0;
        for (Map<String, TimingCell> cells : hourTimes.values()) {
            n += cells.size();
        }
        for (Map<String, TimingCell> cells : dayTimes.values()) {
            n += cells.size();
        }
        return n;
    }

    /** When the oldest counts held started being collected. */
    public Instant countingSince() {
        return countingSince.get();
    }

    /** Grows on every change; lets the persistence skip writing an unchanged snapshot. */
    long changeCount() {
        return changes.sum();
    }

    /** The default body of {@code GET /api/stats}: the last 7 days, per day. */
    public StatsView lastDays() {
        return view(StatsPeriod.LAST_7_DAYS, Granularity.DAY);
    }

    /**
     * Totals, trend, comparison and server times for one window.
     *
     * Hourly windows end with the current (partial) hour, daily windows with the
     * current (partial) UTC day; the previous window is the same number of
     * buckets right before. {@code from} and {@code to} name the first and the
     * last bucket, both included.
     *
     * @throws IllegalArgumentException if the period does not accept the granularity
     */
    public StatsView view(StatsPeriod period, Granularity granularity) {
        if (!period.accepts(granularity)) {
            throw new IllegalArgumentException(period.id() + " does not accept " + granularity.id());
        }
        Instant now = clock.instant();
        List<String> labels = new ArrayList<>();
        List<Map<String, Long>> current = new ArrayList<>();
        List<Map<String, Long>> before = new ArrayList<>();
        List<Map<String, TimingData>> currentTimes = new ArrayList<>();
        String previousFrom;
        String previousTo;
        if (granularity == Granularity.HOUR) {
            int n = period.hours();
            long last = epochHour(now);
            long first = last - n + 1;
            for (long h = first; h <= last; h++) {
                labels.add(hourLabel(h));
                current.add(counts(hours.get(h)));
                before.add(counts(hours.get(h - n)));
                currentTimes.add(times(hourTimes.get(h)));
            }
            previousFrom = hourLabel(first - n);
            previousTo = hourLabel(first - 1);
        } else {
            int n = period.days();
            LocalDate last = LocalDate.ofInstant(now, ZoneOffset.UTC);
            LocalDate first = last.minusDays(n - 1L);
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                labels.add(d.toString());
                current.add(counts(days.get(d)));
                before.add(counts(days.get(d.minusDays(n))));
                currentTimes.add(times(dayTimes.get(d)));
            }
            previousFrom = first.minusDays(n).toString();
            previousTo = first.minusDays(1).toString();
        }

        Map<String, Long> routes = sorted(merge(current));
        Map<String, Long> previousRoutes = sorted(merge(before));
        Map<String, TimingData> routeTimes = mergeTimes(currentTimes);
        Map<String, String> routeGroups = new LinkedHashMap<>();
        routes.keySet().forEach(r -> routeGroups.put(r, RouteGroup.of(r).id()));
        previousRoutes.keySet().forEach(r -> routeGroups.putIfAbsent(r, RouteGroup.of(r).id()));
        routeTimes.keySet().forEach(r -> routeGroups.putIfAbsent(r, RouteGroup.of(r).id()));

        List<Bucket> series = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            Map<String, Long> counts = current.get(i);
            series.add(new Bucket(labels.get(i), sum(counts), groups(counts, false)));
        }
        List<Day> daily = null;
        if (granularity == Granularity.DAY) {
            daily = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                Map<String, Long> counts = sorted(current.get(i));
                daily.add(new Day(labels.get(i), sum(counts), counts));
            }
        }
        Previous previous = new Previous(previousFrom, previousTo, sum(previousRoutes), previousRoutes,
                groups(previousRoutes, true));
        return new StatsView(period.id(), granularity.id(), labels.getFirst(), labels.getLast(), period.days(),
                countingSince().toString(), sum(routes), routes, groups(routes, true), routeGroups, series,
                previous, daily, performance(labels, currentTimes, routeTimes));
    }

    /** Server times per route and per group over the window, and p50/p95 per bucket over all routes. */
    static Performance performance(List<String> labels, List<Map<String, TimingData>> buckets,
                                   Map<String, TimingData> routeTimes) {
        Map<String, Latency> routes = new LinkedHashMap<>();
        routeTimes.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, TimingData>>comparingLong(e -> e.getValue().count())
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> routes.put(e.getKey(), Latency.of(e.getValue())));

        Map<RouteGroup, TimingData> byGroup = new EnumMap<>(RouteGroup.class);
        for (RouteGroup g : RouteGroup.values()) {
            byGroup.put(g, TimingData.EMPTY);
        }
        routeTimes.forEach((route, t) -> byGroup.merge(RouteGroup.of(route), t, TimingData::merge));
        Map<String, Latency> groups = new LinkedHashMap<>();
        byGroup.entrySet().stream()
                .sorted(Comparator.<Map.Entry<RouteGroup, TimingData>>comparingLong(e -> e.getValue().count())
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> groups.put(e.getKey().id(), Latency.of(e.getValue())));

        List<LatencyPoint> series = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            TimingData all = TimingData.EMPTY;
            for (TimingData t : buckets.get(i).values()) {
                all = all.merge(t);
            }
            series.add(new LatencyPoint(labels.get(i), all.count(), ms(all.percentileMicros(0.50)),
                    ms(all.percentileMicros(0.95))));
        }
        return new Performance("ms", routes, groups, series);
    }

    /** A copy of every retained counter and server time, for the snapshot file. */
    StatsData export() {
        Map<Instant, Map<String, Long>> h = new TreeMap<>();
        hours.forEach((hour, counts) -> h.put(Instant.ofEpochSecond(hour * HOUR_SECONDS), counts(counts)));
        Map<LocalDate, Map<String, Long>> d = new TreeMap<>();
        days.forEach((day, counts) -> d.put(day, counts(counts)));
        Map<Instant, Map<String, TimingData>> ht = new TreeMap<>();
        hourTimes.forEach((hour, cells) -> {
            Map<String, TimingData> t = times(cells);
            if (!t.isEmpty()) {
                ht.put(Instant.ofEpochSecond(hour * HOUR_SECONDS), t);
            }
        });
        Map<LocalDate, Map<String, TimingData>> dt = new TreeMap<>();
        dayTimes.forEach((day, cells) -> {
            Map<String, TimingData> t = times(cells);
            if (!t.isEmpty()) {
                dt.put(day, t);
            }
        });
        return new StatsData(countingSince(), h, d, ht, dt);
    }

    /**
     * Adds restored counts and times to the live ones. Adding rather than
     * replacing means a request counted before the restore ran is not lost;
     * buckets older than the retention are dropped.
     */
    void restore(StatsData data) {
        Instant now = clock.instant();
        long oldestHour = epochHour(now) - RETAINED_HOURS + 1;
        LocalDate oldestDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(RETAINED_DAYS - 1L);
        data.hours().forEach((start, counts) -> {
            long hour = epochHour(start);
            if (hour >= oldestHour) {
                add(hours.computeIfAbsent(hour, k -> new ConcurrentHashMap<>()), counts);
            }
        });
        data.days().forEach((day, counts) -> {
            if (!day.isBefore(oldestDay)) {
                add(days.computeIfAbsent(day, k -> new ConcurrentHashMap<>()), counts);
            }
        });
        data.hourTimes().forEach((start, times) -> {
            long hour = epochHour(start);
            if (hour >= oldestHour) {
                addTimes(hourTimes.computeIfAbsent(hour, k -> new ConcurrentHashMap<>()), times);
            }
        });
        data.dayTimes().forEach((day, times) -> {
            if (!day.isBefore(oldestDay)) {
                addTimes(dayTimes.computeIfAbsent(day, k -> new ConcurrentHashMap<>()), times);
            }
        });
        if (data.countingSince() != null) {
            countingSince.accumulateAndGet(data.countingSince(), (a, b) -> a.isBefore(b) ? a : b);
        }
    }

    private static void add(Map<String, LongAdder> into, Map<String, Long> counts) {
        counts.forEach((route, n) -> into.computeIfAbsent(route, r -> new LongAdder()).add(n));
    }

    private static void addTimes(Map<String, TimingCell> into, Map<String, TimingData> times) {
        times.forEach((route, t) -> into.computeIfAbsent(route, r -> new TimingCell()).add(t));
    }

    private static <K, V> Map<String, V> bucket(Map<K, Map<String, V>> buckets, K key, Runnable onNewBucket) {
        Map<String, V> bucket = buckets.get(key);
        if (bucket == null) {
            bucket = buckets.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            onNewBucket.run();
        }
        return bucket;
    }

    private void pruneHours(long currentHour) {
        long oldest = currentHour - RETAINED_HOURS + 1;
        hours.keySet().removeIf(h -> h < oldest);
        hourTimes.keySet().removeIf(h -> h < oldest);
    }

    private void pruneDays(LocalDate today) {
        LocalDate oldest = today.minusDays(RETAINED_DAYS - 1L);
        days.keySet().removeIf(d -> d.isBefore(oldest));
        dayTimes.keySet().removeIf(d -> d.isBefore(oldest));
    }

    static long epochHour(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), HOUR_SECONDS);
    }

    private static String hourLabel(long epochHour) {
        return Instant.ofEpochSecond(epochHour * HOUR_SECONDS).toString();
    }

    private static Map<String, Long> counts(Map<String, LongAdder> bucket) {
        Map<String, Long> counts = new HashMap<>();
        if (bucket != null) {
            bucket.forEach((route, adder) -> counts.put(route, adder.sum()));
        }
        return counts;
    }

    private static Map<String, TimingData> times(Map<String, TimingCell> bucket) {
        Map<String, TimingData> times = new HashMap<>();
        if (bucket != null) {
            bucket.forEach((route, cell) -> {
                TimingData t = cell.data();
                if (t.count() > 0) {
                    times.put(route, t);
                }
            });
        }
        return times;
    }

    private static Map<String, Long> merge(List<Map<String, Long>> buckets) {
        Map<String, Long> totals = new HashMap<>();
        buckets.forEach(b -> b.forEach((route, n) -> totals.merge(route, n, Long::sum)));
        return totals;
    }

    static Map<String, TimingData> mergeTimes(List<Map<String, TimingData>> buckets) {
        Map<String, TimingData> totals = new HashMap<>();
        buckets.forEach(b -> b.forEach((route, t) -> totals.merge(route, t, TimingData::merge)));
        return totals;
    }

    /**
     * Totals per {@link RouteGroup}, by count. With {@code all}, every group is
     * present (zeros included) so the legend never changes shape; without it,
     * only the groups that had requests, which keeps long series small.
     */
    static Map<String, Long> groups(Map<String, Long> routes, boolean all) {
        Map<RouteGroup, Long> byGroup = new EnumMap<>(RouteGroup.class);
        if (all) {
            for (RouteGroup g : RouteGroup.values()) {
                byGroup.put(g, 0L);
            }
        }
        routes.forEach((route, n) -> byGroup.merge(RouteGroup.of(route), n, Long::sum));
        Map<String, Long> result = new LinkedHashMap<>();
        byGroup.entrySet().stream()
                .sorted(Map.Entry.<RouteGroup, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> result.put(e.getKey().id(), e.getValue()));
        return result;
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Map<String, Long> sorted(Map<String, Long> counts) {
        Map<String, Long> result = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** Microseconds to milliseconds with three decimals; null for "no data" (NaN). */
    static Double ms(double micros) {
        if (Double.isNaN(micros)) {
            return null;
        }
        return Math.round(micros) / 1000.0;
    }

    /**
     * Public body of {@code GET /api/stats}. Times are UTC. {@code days},
     * {@code countingSince}, {@code total}, {@code routes} and (for daily
     * windows) {@code daily} keep the shape of 2.6.0.
     *
     * @param period        {@code 24h}, {@code 7d} or {@code 30d}
     * @param granularity   {@code hour} or {@code day}
     * @param from          first bucket of the window (an hour start or a date)
     * @param to            last bucket of the window, included; it is still filling up
     * @param days          length of the window in days (1 for 24 h)
     * @param countingSince when the oldest counts held started being collected
     * @param total         requests in the window
     * @param routes        requests per route template, by count
     * @param groups        requests per group ({@code rest}, {@code mcp}, {@code pages}, {@code seo},
     *                      {@code other}), by count, all five present
     * @param routeGroups   the group of every route that appears in {@code routes}, {@code previous}
     *                      or {@code performance}
     * @param series        one bucket per hour or per day, oldest first
     * @param previous      the window of the same length right before this one
     * @param daily         per-day route counts, daily windows only (absent for hourly ones)
     * @param performance   server times in the window (2.8.0+)
     */
    @RegisterForReflection
    public record StatsView(String period, String granularity, String from, String to, int days,
                            String countingSince, long total, Map<String, Long> routes,
                            Map<String, Long> groups, Map<String, String> routeGroups,
                            List<Bucket> series, Previous previous, List<Day> daily,
                            Performance performance) {
    }

    /** One point of the trend: requests in the bucket, per group (only groups with requests). */
    @RegisterForReflection
    public record Bucket(String start, long total, Map<String, Long> groups) {
    }

    /** The window before, for the comparison. */
    @RegisterForReflection
    public record Previous(String from, String to, long total, Map<String, Long> routes,
                           Map<String, Long> groups) {
    }

    @RegisterForReflection
    public record Day(String date, long total, Map<String, Long> routes) {
    }

    /**
     * Server times in the window: from when the server saw the request to when
     * the response was fully written. The network between the client and the
     * server is not included.
     *
     * @param unit   always {@code ms}
     * @param routes times per route template that had timed requests, by count
     * @param groups times per group, all five present (a group without requests has only {@code count: 0})
     * @param series p50 and p95 over all routes per hour or per day, oldest first, aligned with
     *               {@link StatsView#series()}
     */
    @RegisterForReflection
    public record Performance(String unit, Map<String, Latency> routes, Map<String, Latency> groups,
                              List<LatencyPoint> series) {
    }

    /**
     * Server times of a route or a group, in milliseconds with three decimals.
     * Mean, minimum, maximum and standard deviation (population) are exact;
     * the percentiles are estimated from fixed buckets, within about 9 %
     * ({@link LatencyHistogram}). Without requests only {@code count} (0) is present.
     *
     * @param count timed requests (requests whose response was fully written)
     */
    @RegisterForReflection
    public record Latency(long count, Double mean, Double min, Double max, Double stddev, Double p50,
                          Double p95, Double p99) {

        static Latency of(TimingData t) {
            if (t.count() == 0) {
                return new Latency(0, null, null, null, null, null, null, null);
            }
            return new Latency(t.count(), ms(t.meanMicros()), ms(t.minMicros()), ms(t.maxMicros()),
                    ms(t.stddevMicros()), ms(t.percentileMicros(0.50)), ms(t.percentileMicros(0.95)),
                    ms(t.percentileMicros(0.99)));
        }
    }

    /** p50 and p95 of one hour or day over all routes; both absent when the bucket had no timed request. */
    @RegisterForReflection
    public record LatencyPoint(String start, long count, Double p50, Double p95) {
    }
}
