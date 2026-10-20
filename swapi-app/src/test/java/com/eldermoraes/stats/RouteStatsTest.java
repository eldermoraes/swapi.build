package com.eldermoraes.stats;

import com.eldermoraes.stats.StatsPeriod.Granularity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteStatsTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:30:00Z"));
    private final RouteStats stats = new RouteStats(clock);

    @Test
    void countsPerRouteAndPerDaySortedByCount() {
        stats.record("/mcp");
        stats.record("/api/people/{id}");
        stats.record("/api/people/{id}");

        assertEquals(Map.of("/api/people/{id}", 2L, "/mcp", 1L), stats.day(LocalDate.parse("2026-10-19")));
        assertEquals(List.of("/api/people/{id}", "/mcp"),
                List.copyOf(stats.day(LocalDate.parse("2026-10-19")).keySet()));
    }

    @Test
    void eachRequestAlsoLandsInItsHour() {
        stats.record("/mcp");                               // 12:30
        clock.advance(Duration.ofMinutes(29));
        stats.record("/mcp");                               // 12:59
        clock.advance(Duration.ofMinutes(1));
        stats.record("/api/films");                         // 13:00

        assertEquals(Map.of("/mcp", 2L), stats.hour(Instant.parse("2026-10-19T12:00:00Z")));
        assertEquals(Map.of("/api/films", 1L), stats.hour(Instant.parse("2026-10-19T13:00:00Z")));
        assertEquals(Map.of("/mcp", 2L, "/api/films", 1L), stats.day(LocalDate.parse("2026-10-19")));
    }

    @Test
    void dayBoundaryIsUtcMidnight() {
        clock.now = Instant.parse("2026-10-19T23:59:59Z");
        stats.record("/mcp");
        clock.advance(Duration.ofSeconds(1));
        stats.record("/mcp");

        assertEquals(Map.of("/mcp", 1L), stats.day(LocalDate.parse("2026-10-19")));
        assertEquals(Map.of("/mcp", 1L), stats.day(LocalDate.parse("2026-10-20")));
    }

    @Test
    void defaultViewKeepsTheShapeOf260() {
        stats.record("/api/films");                      // 2026-10-19
        clock.advance(Duration.ofDays(6));
        stats.record("/api/films");                      // 2026-10-25
        stats.record("/mcp");

        RouteStats.StatsView view = stats.lastDays();
        assertEquals("7d", view.period());
        assertEquals("day", view.granularity());
        assertEquals("2026-10-19", view.from());
        assertEquals("2026-10-25", view.to());
        assertEquals(7, view.days());
        assertEquals(7, view.daily().size());
        assertEquals(3, view.total());
        assertEquals(Map.of("/api/films", 2L, "/mcp", 1L), view.routes());
        assertEquals("2026-10-19", view.daily().get(0).date());
        assertEquals(1, view.daily().get(0).total());
        assertEquals(0, view.daily().get(3).total());
        assertEquals("2026-10-19T12:30:00Z", view.countingSince());
    }

    @Test
    void twentyFourHoursIsHourlyAndEndsWithTheCurrentHour() {
        clock.now = Instant.parse("2026-10-18T12:10:00Z");
        stats.record("/mcp");                            // previous window: its last hour
        clock.now = Instant.parse("2026-10-19T12:59:00Z");
        stats.record("/mcp");                            // last bucket
        clock.now = Instant.parse("2026-10-18T14:00:00Z");
        stats.record("/api/films");                      // first bucket
        clock.now = Instant.parse("2026-10-19T12:59:00Z");

        RouteStats.StatsView view = stats.view(StatsPeriod.LAST_24_HOURS, Granularity.HOUR);
        assertEquals("hour", view.granularity());
        assertEquals(24, view.series().size());
        assertEquals("2026-10-18T13:00:00Z", view.from());
        assertEquals("2026-10-19T12:00:00Z", view.to());
        assertEquals(2, view.total());
        assertEquals(1, view.series().get(1).total());
        assertEquals(1, view.series().get(23).total());
        assertEquals(Map.of("mcp", 1L), view.series().get(23).groups());
        assertNull(view.daily(), "hourly windows carry no per-day list");
        assertEquals(0, view.series().get(0).total());
        assertEquals(1, view.previous().total());
        assertEquals("2026-10-17T13:00:00Z", view.previous().from());
        assertEquals("2026-10-18T12:00:00Z", view.previous().to());
    }

    @Test
    void previousWindowIsTheSameLengthRightBefore() {
        clock.now = Instant.parse("2026-10-12T08:00:00Z");
        stats.record("/mcp");                            // previous 7 days: 10-06 .. 10-12
        stats.record("/mcp");
        clock.now = Instant.parse("2026-10-13T08:00:00Z");
        stats.record("/mcp");                            // current 7 days: 10-13 .. 10-19
        clock.now = Instant.parse("2026-10-19T08:00:00Z");

        RouteStats.StatsView view = stats.view(StatsPeriod.LAST_7_DAYS, Granularity.DAY);
        assertEquals(1, view.total());
        assertEquals("2026-10-06", view.previous().from());
        assertEquals("2026-10-12", view.previous().to());
        assertEquals(2, view.previous().total());
        assertEquals(Map.of("/mcp", 2L), view.previous().routes());
        assertEquals(2L, view.previous().groups().get("mcp"));
    }

    @Test
    void thirtyDaysAndItsPreviousWindowStayInMemory() {
        clock.now = Instant.parse("2026-08-21T10:00:00Z");   // 59 days before 10-19: oldest previous day
        stats.record("/api/films");
        clock.now = Instant.parse("2026-10-19T10:00:00Z");
        stats.record("/api/films");

        RouteStats.StatsView view = stats.view(StatsPeriod.LAST_30_DAYS, Granularity.DAY);
        assertEquals(30, view.series().size());
        assertEquals(30, view.days());
        assertEquals("2026-09-20", view.from());
        assertEquals(1, view.total());
        assertEquals("2026-08-21", view.previous().from());
        assertEquals(1, view.previous().total());
    }

    @Test
    void sevenDaysCanBeHourly() {
        clock.now = Instant.parse("2026-10-05T13:00:00Z");   // first hour of the previous 168
        stats.record("/mcp");
        clock.now = Instant.parse("2026-10-19T12:00:00Z");
        stats.record("/mcp");

        RouteStats.StatsView view = stats.view(StatsPeriod.LAST_7_DAYS, Granularity.HOUR);
        assertEquals(168, view.series().size());
        assertEquals(1, view.total());
        assertEquals(1, view.previous().total());
    }

    @Test
    void periodsRejectGranularitiesTheyCannotServe() {
        assertThrows(IllegalArgumentException.class, () -> stats.view(StatsPeriod.LAST_24_HOURS, Granularity.DAY));
        assertThrows(IllegalArgumentException.class, () -> stats.view(StatsPeriod.LAST_30_DAYS, Granularity.HOUR));
    }

    @Test
    void groupsAreTotalledAndAllFivePresent() {
        stats.record("/api/people/{id}");
        stats.record("/openapi.json");
        stats.record("/mcp");
        stats.record("/docs");
        stats.record("/sitemap.xml");
        stats.record("other");
        stats.record("other");

        RouteStats.StatsView view = stats.lastDays();
        assertEquals(Map.of("rest", 2L, "other", 2L, "mcp", 1L, "pages", 1L, "seo", 1L), view.groups());
        // By count, ties in the fixed group order.
        assertEquals(List.of("rest", "other", "mcp", "pages", "seo"), List.copyOf(view.groups().keySet()));
        assertEquals("rest", view.routeGroups().get("/openapi.json"));
        assertEquals("seo", view.routeGroups().get("/sitemap.xml"));
    }

    @Test
    void hourlyBucketsArePrunedAfterTheirRetention() {
        stats.record("/api/films");                      // 2026-10-19T12
        clock.advance(Duration.ofHours(RouteStats.RETAINED_HOURS));
        stats.record("/mcp");

        assertTrue(stats.hour(Instant.parse("2026-10-19T12:00:00Z")).isEmpty());
        // The day is still within its own, longer retention.
        assertEquals(Map.of("/api/films", 1L), stats.day(LocalDate.parse("2026-10-19")));
    }

    @Test
    void hourlyBucketsInsideTheRetentionStay() {
        stats.record("/api/films");
        clock.advance(Duration.ofHours(RouteStats.RETAINED_HOURS - 1L));
        stats.record("/mcp");

        assertEquals(Map.of("/api/films", 1L), stats.hour(Instant.parse("2026-10-19T12:00:00Z")));
    }

    @Test
    void dailyBucketsArePrunedAfterTheirRetention() {
        stats.record("/api/films");                      // 2026-10-19
        clock.advance(Duration.ofDays(RouteStats.RETAINED_DAYS));
        stats.record("/mcp");

        assertTrue(stats.day(LocalDate.parse("2026-10-19")).isEmpty());
    }

    @Test
    void exportAndRestoreRoundTripAddingToLiveCounts() {
        stats.record("/mcp");
        clock.advance(Duration.ofHours(2));
        stats.record("/api/films");
        StatsData saved = stats.export();

        RouteStats fresh = new RouteStats(clock);
        fresh.record("/mcp");                            // counted before the restore ran
        fresh.restore(saved);

        assertEquals(Map.of("/mcp", 2L, "/api/films", 1L), fresh.day(LocalDate.parse("2026-10-19")));
        assertEquals(Map.of("/mcp", 1L), fresh.hour(Instant.parse("2026-10-19T12:00:00Z")));
        assertEquals(Instant.parse("2026-10-19T12:30:00Z"), fresh.countingSince(),
                "countingSince goes back to the oldest restored counts");
    }

    @Test
    void restoreDropsBucketsOlderThanTheRetention() {
        StatsData old = new StatsData(Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(Instant.parse("2026-10-01T00:00:00Z"), Map.of("/mcp", 5L)),
                Map.of(LocalDate.parse("2026-08-01"), Map.of("/mcp", 5L),
                        LocalDate.parse("2026-10-01"), Map.of("/mcp", 3L)));
        stats.restore(old);

        assertTrue(stats.hour(Instant.parse("2026-10-01T00:00:00Z")).isEmpty());
        assertTrue(stats.day(LocalDate.parse("2026-08-01")).isEmpty());
        assertEquals(Map.of("/mcp", 3L), stats.day(LocalDate.parse("2026-10-01")));
    }

    @Test
    void changeCountGrowsWithEveryRecord() {
        long before = stats.changeCount();
        stats.record("/mcp");
        stats.record("/mcp");
        assertEquals(before + 2, stats.changeCount());
    }

    @Test
    void concurrentRecordsAreNotLost() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8000; i++) {
            pool.submit(() -> stats.record("/mcp"));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(8000L, stats.day(LocalDate.parse("2026-10-19")).get("/mcp"));
        assertEquals(8000L, stats.hour(Instant.parse("2026-10-19T12:00:00Z")).get("/mcp"));
    }
}
