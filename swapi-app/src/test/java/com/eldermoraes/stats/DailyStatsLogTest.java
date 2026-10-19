package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyStatsLogTest {

    @Test
    void lineCarriesDateTotalAndRoutesOnly() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("/api/people/{id}", 7L);
        counts.put("/mcp", 5L);

        assertEquals("route-stats 2026-10-19 UTC total=12 /api/people/{id}=7 /mcp=5",
                DailyStatsLog.line(LocalDate.parse("2026-10-19"), counts, false));
    }

    @Test
    void partialDayIsMarked() {
        assertEquals("route-stats 2026-10-19 UTC (partial, until shutdown) total=1 /mcp=1",
                DailyStatsLog.line(LocalDate.parse("2026-10-19"), Map.of("/mcp", 1L), true));
    }

    @Test
    void emptyDayStillLogsATotal() {
        assertEquals("route-stats 2026-10-19 UTC total=0",
                DailyStatsLog.line(LocalDate.parse("2026-10-19"), Map.of(), false));
    }
}
