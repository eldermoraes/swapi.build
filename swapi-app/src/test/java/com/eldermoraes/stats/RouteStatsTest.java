package com.eldermoraes.stats;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteStatsTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

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

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
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
    void dayBoundaryIsUtcMidnight() {
        clock.now = Instant.parse("2026-10-19T23:59:59Z");
        stats.record("/mcp");
        clock.advance(Duration.ofSeconds(1));
        stats.record("/mcp");

        assertEquals(Map.of("/mcp", 1L), stats.day(LocalDate.parse("2026-10-19")));
        assertEquals(Map.of("/mcp", 1L), stats.day(LocalDate.parse("2026-10-20")));
    }

    @Test
    void snapshotCoversTodayAndTheSixDaysBefore() {
        stats.record("/api/films");                      // 2026-10-19
        clock.advance(Duration.ofDays(6));
        stats.record("/api/films");                      // 2026-10-25
        stats.record("/mcp");

        RouteStats.Snapshot snapshot = stats.lastDays();
        assertEquals("2026-10-19", snapshot.from());
        assertEquals("2026-10-25", snapshot.to());
        assertEquals(7, snapshot.days());
        assertEquals(7, snapshot.daily().size());
        assertEquals(3, snapshot.total());
        assertEquals(Map.of("/api/films", 2L, "/mcp", 1L), snapshot.routes());
        assertEquals("2026-10-19", snapshot.daily().get(0).date());
        assertEquals(1, snapshot.daily().get(0).total());
        assertEquals(0, snapshot.daily().get(3).total());
        assertEquals("2026-10-19T12:00:00Z", snapshot.countingSince());
    }

    @Test
    void daysOutsideTheWindowLeaveTheSnapshot() {
        stats.record("/api/films");                      // 2026-10-19
        clock.advance(Duration.ofDays(7));
        stats.record("/mcp");                            // 2026-10-26

        RouteStats.Snapshot snapshot = stats.lastDays();
        assertEquals("2026-10-20", snapshot.from());
        assertEquals(Map.of("/mcp", 1L), snapshot.routes());
        // Still retained for one more day: yesterday-of-the-window is what the daily log reads.
        assertEquals(Map.of("/api/films", 1L), stats.day(LocalDate.parse("2026-10-19")));
    }

    @Test
    void oldDaysArePrunedFromMemory() {
        stats.record("/api/films");                      // 2026-10-19
        clock.advance(Duration.ofDays(RouteStats.RETAINED_DAYS));
        stats.record("/mcp");

        assertTrue(stats.day(LocalDate.parse("2026-10-19")).isEmpty());
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
    }
}
