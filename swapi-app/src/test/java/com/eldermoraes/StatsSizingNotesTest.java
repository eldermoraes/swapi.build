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
    private static final int WORST_CASE = RouteNormalizer.TEMPLATE_COUNT * BUCKETS;

    @Test
    void worstCaseIsTheOneTheNotesWorkFrom() {
        assertEquals(23_364, WORST_CASE, "59 x (336 + 60): the templates or the retention changed. Redo the "
                + "arithmetic in the class comment of RouteStats, in the comment on SnapshotFile.MAX_BYTES and "
                + "in docs/DEPLOY.md (\"Usage counters\", \"Server times\"), then update this number");
    }

    @Test
    void runbookStatesTheSameNumbers() throws IOException {
        String runbook = Files.readString(ReleaseMetadata.repoRoot().resolve("docs").resolve("DEPLOY.md"))
                .replaceAll("\\s+", " ");
        String templates = "all " + RouteNormalizer.TEMPLATE_COUNT + " route templates";
        String product = RouteNormalizer.TEMPLATE_COUNT + " x " + BUCKETS + " = "
                + String.format(Locale.ROOT, "%,d", WORST_CASE);

        assertTrue(runbook.contains(templates), "docs/DEPLOY.md should say \"" + templates + "\"");
        assertTrue(runbook.contains(product), "docs/DEPLOY.md should say \"" + product + "\"");
    }
}
