package com.eldermoraes.stats;

import com.eldermoraes.stats.StatsPeriod.Granularity;
import com.eldermoraes.stats.StatsViewMemo.Served;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The memo in front of {@link RouteStats#view} (#53), with a clock moved by hand. */
class StatsViewMemoTest {

    private final RouteStatsTest.MutableClock clock =
            new RouteStatsTest.MutableClock(Instant.parse("2026-10-19T12:30:00Z"));
    private final RouteStats stats = new RouteStats(clock);

    private StatsViewMemo memo(Duration lifetime) {
        return new StatsViewMemo(stats, clock, lifetime);
    }

    private static Served sevenDays(StatsViewMemo memo) {
        return memo.view(StatsPeriod.LAST_7_DAYS, Granularity.DAY);
    }

    @Test
    void aViewIsServedAgainWithinItsLifetime() {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        stats.record("/mcp");
        Served first = sevenDays(memo);
        stats.record("/mcp");
        clock.advance(Duration.ofMillis(59_999));

        Served again = sevenDays(memo);
        assertSame(first.view(), again.view());
        assertEquals(1, again.view().total(), "the request recorded after the build is not in it yet");
    }

    @Test
    void aViewIsBuiltAgainOnceItsLifetimeIsOver() {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        stats.record("/mcp");
        sevenDays(memo);
        stats.record("/mcp");
        clock.advance(Duration.ofSeconds(60));

        Served rebuilt = sevenDays(memo);
        assertEquals(2, rebuilt.view().total());
        assertEquals(60, rebuilt.maxAgeSeconds());
    }

    @Test
    void theAdvertisedLifetimeShrinksWithTheAgeOfTheView() {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        assertEquals(60, sevenDays(memo).maxAgeSeconds(), "just built");

        clock.advance(Duration.ofMillis(1));
        assertEquals(59, sevenDays(memo).maxAgeSeconds(), "rounded down: age plus max-age never passes 60 s");
        clock.advance(Duration.ofMillis(19_999));
        assertEquals(40, sevenDays(memo).maxAgeSeconds());
        clock.advance(Duration.ofMillis(39_999));
        assertEquals(0, sevenDays(memo).maxAgeSeconds(), "59.999 s old");
    }

    @Test
    void eachPeriodAndGranularityHasItsOwnView() {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        Served daily = sevenDays(memo);
        stats.record("/mcp");

        Served hourly = memo.view(StatsPeriod.LAST_7_DAYS, Granularity.HOUR);
        assertEquals("hour", hourly.view().granularity());
        assertEquals(1, hourly.view().total(), "built now, not taken from the daily view");
        assertEquals(1, memo.view(StatsPeriod.LAST_24_HOURS, Granularity.HOUR).view().total());
        assertEquals("30d", memo.view(StatsPeriod.LAST_30_DAYS, Granularity.DAY).view().period());
        assertSame(daily.view(), sevenDays(memo).view());
        assertSame(hourly.view(), memo.view(StatsPeriod.LAST_7_DAYS, Granularity.HOUR).view());
    }

    @Test
    void aLifetimeOfZeroBuildsAViewForEveryRequest() {
        StatsViewMemo memo = memo(Duration.ZERO);
        stats.record("/mcp");
        Served first = sevenDays(memo);
        stats.record("/mcp");

        Served second = sevenDays(memo);
        assertEquals(1, first.view().total());
        assertEquals(2, second.view().total());
        assertEquals(60, second.maxAgeSeconds());
    }

    @Test
    void aLifetimeAboveTheAdvertisedOneCountsAsTheAdvertisedOne() {
        StatsViewMemo memo = memo(Duration.ofHours(1));
        Served first = sevenDays(memo);
        clock.advance(Duration.ofSeconds(60));

        assertNotSame(first.view(), sevenDays(memo).view());
    }

    @Test
    void aClockThatStepsBackMakesTheViewStale() {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        Served first = sevenDays(memo);
        clock.advance(Duration.ofSeconds(-5));

        Served second = sevenDays(memo);
        assertNotSame(first.view(), second.view());
        assertEquals(60, second.maxAgeSeconds());
    }

    @Test
    void requestsArrivingTogetherShareOneBuild() throws Exception {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        stats.record("/mcp");
        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Served>> answers = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return memo.view(StatsPeriod.LAST_7_DAYS, Granularity.HOUR);
                }));
            }
            start.countDown();

            RouteStats.StatsView one = answers.getFirst().get().view();
            for (Future<Served> answer : answers) {
                assertSame(one, answer.get().view());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPeriodThatDoesNotAcceptTheGranularityIsRejected() {
        StatsViewMemo memo = memo(Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> memo.view(StatsPeriod.LAST_24_HOURS, Granularity.DAY));
        assertThrows(IllegalArgumentException.class, () -> memo.view(StatsPeriod.LAST_30_DAYS, Granularity.HOUR));
    }
}
