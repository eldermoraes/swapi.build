package com.eldermoraes.stats;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Request counters per route template and per UTC day, in memory only.
 *
 * Nothing about the client is ever passed in: the only input is the route
 * template produced by {@link RouteNormalizer}. No IP, no User-Agent, no query
 * string, no id.
 *
 * The host runs the app with a read-only disk ({@code ProtectSystem=strict}) and
 * a 300 MB ceiling, so the counters are never persisted and a restart (a deploy,
 * for one) starts them from zero; {@link Snapshot#countingSince()} says when.
 * Memory is bounded twice: the route set is closed, and days older than
 * {@link #RETAINED_DAYS} are dropped.
 */
@Singleton
public class RouteStats {

    /** Days returned by {@link #lastDays}: today (UTC) and the six before it. */
    public static final int WINDOW_DAYS = 7;

    /** One day more than the window, so yesterday is still there for the daily log line. */
    static final int RETAINED_DAYS = WINDOW_DAYS + 1;

    private final Map<LocalDate, Map<String, LongAdder>> days = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Instant countingSince;

    public RouteStats() {
        this(Clock.systemUTC());
    }

    RouteStats(Clock clock) {
        this.clock = clock;
        this.countingSince = clock.instant();
    }

    /** Counts one request on a route template from {@link RouteNormalizer}. */
    public void record(String route) {
        LocalDate today = today();
        Map<String, LongAdder> day = days.get(today);
        if (day == null) {
            day = days.computeIfAbsent(today, d -> new ConcurrentHashMap<>());
            prune(today);
        }
        day.computeIfAbsent(route, r -> new LongAdder()).increment();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Counts of one day, sorted by count (descending), then by route. */
    public Map<String, Long> day(LocalDate date) {
        Map<String, LongAdder> day = days.get(date);
        Map<String, Long> counts = new HashMap<>();
        if (day != null) {
            day.forEach((route, adder) -> counts.put(route, adder.sum()));
        }
        return sorted(counts);
    }

    /** Totals of the last {@link #WINDOW_DAYS} UTC days, today included. */
    public Snapshot lastDays() {
        LocalDate to = today();
        LocalDate from = to.minusDays(WINDOW_DAYS - 1L);
        Map<String, Long> totals = new HashMap<>();
        List<Day> perDay = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Map<String, Long> counts = day(date);
            counts.forEach((route, n) -> totals.merge(route, n, Long::sum));
            perDay.add(new Day(date.toString(), sum(counts), counts));
        }
        Map<String, Long> routes = sorted(totals);
        return new Snapshot(from.toString(), to.toString(), WINDOW_DAYS, countingSince.toString(),
                sum(routes), routes, perDay);
    }

    private void prune(LocalDate today) {
        LocalDate oldest = today.minusDays(RETAINED_DAYS - 1L);
        days.keySet().removeIf(date -> date.isBefore(oldest));
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

    /**
     * Public body of {@code GET /api/stats}. Dates are UTC; {@code countingSince}
     * is the process start, before which nothing was counted.
     */
    @RegisterForReflection
    public record Snapshot(String from, String to, int days, String countingSince, long total,
                           Map<String, Long> routes, List<Day> daily) {
    }

    @RegisterForReflection
    public record Day(String date, long total, Map<String, Long> routes) {
    }
}
