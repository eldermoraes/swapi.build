package com.eldermoraes.stats;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One journal line per UTC day with that day's counts.
 *
 * Why a log line: it is a second, independent record of the daily totals that
 * does not depend on the state directory ({@link StatsPersistence}), which a
 * host may not have. The journal keeps the host's logs for 30 days
 * (docs/DEPLOY.md, journald retention), so one line a day gives a month of
 * history across restarts at the cost of a few hundred bytes. The line carries
 * only route templates and counts, like {@code GET /api/stats}.
 *
 * The line is written at 00:00 UTC for the day that just ended, and on shutdown
 * for the partial current day, so a deploy does not silently drop its numbers.
 */
@ApplicationScoped
public class DailyStatsLog {

    private static final Logger LOG = Logger.getLogger(DailyStatsLog.class);

    @Inject
    RouteStats stats;

    @Scheduled(cron = "0 0 0 * * ?", timeZone = "UTC", identity = "daily-route-stats")
    void endOfDay() {
        LocalDate yesterday = stats.today().minusDays(1);
        LOG.info(line(yesterday, stats.day(yesterday), false));
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LocalDate today = stats.today();
        LOG.info(line(today, stats.day(today), true));
    }

    /** {@code route-stats 2026-10-19 UTC total=12 /api/people/{id}=7 /mcp=5}. */
    static String line(LocalDate date, Map<String, Long> counts, boolean partial) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        String routes = counts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "));
        return "route-stats " + date + " UTC" + (partial ? " (partial, until shutdown)" : "")
                + " total=" + total + (routes.isEmpty() ? "" : " " + routes);
    }
}
