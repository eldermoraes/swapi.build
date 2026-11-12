package com.eldermoraes;

import com.eldermoraes.stats.RouteNormalizer;
import com.eldermoraes.stats.RouteStats;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the sizing notes of the statistics on the numbers the code has (#60:
 * they said 45 route templates when there were 59). The number of templates is
 * {@link RouteNormalizer#TEMPLATE_COUNT}, which {@code RouteNormalizerTest}
 * holds to what the normalizer produces; this holds the worst case derived
 * from it, and the runbook, to that constant.
 */
class StatsSizingNotesTest {

    private static final int BUCKETS = RouteStats.RETAINED_HOURS + RouteStats.RETAINED_DAYS;
    private static final int WORST_CASE_CELLS = RouteNormalizer.TEMPLATE_COUNT * BUCKETS;

    @Test
    void worstCaseIsTheOneTheNotesWorkFrom() {
        assertEquals(23_364, WORST_CASE_CELLS, "59 x (336 + 60): the templates or the retention changed. Redo the "
                + "arithmetic in the class comment of RouteStats, in the comment on SnapshotFile.MAX_BYTES and "
                + "in docs/DEPLOY.md (\"Usage counters\", \"Server times\"), then update this number");
    }

    @Test
    void runbookStatesTheSameNumbersInBothParagraphs() throws IOException {
        String runbook = Files.readString(ReleaseMetadata.repoRoot().resolve("docs").resolve("DEPLOY.md"))
                .replaceAll("\\s+", " ");
        String counters = between(runbook, "**Usage counters (", "**Server times (");
        String times = between(runbook, "**Server times (", "**View lifetime.");
        String templates = "all " + RouteNormalizer.TEMPLATE_COUNT + " route templates";
        String cells = String.format(Locale.ROOT, "%,d", WORST_CASE_CELLS);
        String product = RouteNormalizer.TEMPLATE_COUNT + " x " + BUCKETS + " = " + cells;

        assertTrue(counters.contains(templates), "\"Usage counters\" should say \"" + templates + "\"");
        assertTrue(counters.contains(product), "\"Usage counters\" should say \"" + product + "\"");
        assertTrue(times.contains(templates), "\"Server times\" should say \"" + templates + "\"");
        assertTrue(times.contains(cells + " cells"), "\"Server times\" should say \"" + cells + " cells\"");
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, Math.max(start, 0));
        assertTrue(start >= 0 && end > start, "docs/DEPLOY.md: no \"" + from + "\" paragraph before \"" + to + "\"");
        return text.substring(start, end);
    }
}
