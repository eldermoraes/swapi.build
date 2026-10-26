package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotFileTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    private SnapshotFile file() {
        return new SnapshotFile(dir, warnings::add);
    }

    private static TimingData times(long... micros) {
        TimingCell cell = new TimingCell();
        for (long m : micros) {
            cell.record(m);
        }
        return cell.data();
    }

    private static StatsData sample() {
        return new StatsData(Instant.parse("2026-10-19T12:30:00Z"),
                Map.of(Instant.parse("2026-10-19T12:00:00Z"), Map.of("/api/people/{id}", 7L, "/mcp", 5L)),
                Map.of(LocalDate.parse("2026-10-19"), Map.of("/api/people/{id}", 7L, "/mcp", 5L, "other", 1L)),
                Map.of(Instant.parse("2026-10-19T12:00:00Z"), Map.of("/mcp", times(900, 1200, 1800))),
                Map.of(LocalDate.parse("2026-10-19"), Map.of("/mcp", times(900, 1200, 1800))));
    }

    @Test
    void roundTrips() throws IOException {
        file().write(sample());

        assertEquals(Optional.of(sample()), file().read());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void fileHoldsOnlyRoutesBucketsCountsAndTimes() throws IOException {
        file().write(sample());

        assertEquals("""
                swapi-route-stats 2
                since 2026-10-19T12:30:00Z
                h 2026-10-19T12:00:00Z /api/people/{id} 7
                h 2026-10-19T12:00:00Z /mcp 5
                d 2026-10-19 /api/people/{id} 7
                d 2026-10-19 /mcp 5
                d 2026-10-19 other 1
                th 2026-10-19T12:00:00Z /mcp 3 3900 5490000.0 900 1800 17:1,19:1,21:1
                td 2026-10-19 /mcp 3 3900 5490000.0 900 1800 17:1,19:1,21:1
                """, Files.readString(dir.resolve(SnapshotFile.FILE_NAME)));
    }

    @Test
    void aFormatOneSnapshotWithoutTimesStillLoads() throws IOException {
        // What 2.7.0 wrote: counts only.
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 1
                since 2026-10-19T12:30:00Z
                h 2026-10-19T12:00:00Z /mcp 5
                d 2026-10-19 /mcp 5
                """, StandardCharsets.UTF_8);

        StatsData data = file().read().orElseThrow();
        assertEquals(Map.of(LocalDate.parse("2026-10-19"), Map.of("/mcp", 5L)), data.days());
        assertTrue(data.hourTimes().isEmpty());
        assertTrue(data.dayTimes().isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aSnapshotWithoutTimesIsWrittenAsFormatTwoAndReadBack() throws IOException {
        StatsData countsOnly = new StatsData(Instant.parse("2026-10-19T12:30:00Z"), Map.of(),
                Map.of(LocalDate.parse("2026-10-19"), Map.of("/mcp", 1L)));
        file().write(countsOnly);

        assertEquals(Optional.of(countsOnly), file().read());
    }

    @Test
    void writeReplacesThePreviousSnapshotAndLeavesNoTemporaryFile() throws IOException {
        file().write(sample());
        StatsData next = new StatsData(Instant.parse("2026-10-19T12:30:00Z"), Map.of(),
                Map.of(LocalDate.parse("2026-10-20"), Map.of("/mcp", 1L)));
        file().write(next);

        assertEquals(Optional.of(next), file().read());
        try (var files = Files.list(dir)) {
            assertEquals(List.of(SnapshotFile.FILE_NAME), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void missingFileIsAQuietFirstStart() {
        assertEquals(Optional.empty(), file().read());
        assertTrue(warnings.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                                                         // empty
            "garbage\n",                                                // no header
            "swapi-route-stats 3\nd 2026-10-19 /mcp 1\n",               // unknown version
            // Server times (format 2).
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 1 900 810000.0 900 900\n",        // missing histogram
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 2 900 810000.0 900 900 17:1\n",   // histogram short of count
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 0 0 0.0 0 0 17:0\n",              // empty cell
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 1 900 810000.0 950 900 17:1\n",   // min above max
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 1 900 NaN 900 900 17:1\n",        // not a number
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 1 900 -1.0 900 900 17:1\n",       // negative squares
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 1 900 810000.0 900 900 84:1\n",   // no such bucket
            "swapi-route-stats 2\ntd 2026-10-19 /mcp 2 900 810000.0 900 900 17:1,17:1\n", // bucket twice
            "swapi-route-stats 2\nth 2026-10-19T12:30:00Z /mcp 1 900 810000.0 900 900 17:1\n", // not an hour
            "swapi-route-stats 1\nd 2026-10-19 /mcp\n",                 // missing field
            "swapi-route-stats 1\nd 2026-10-19 /mcp -1\n",              // negative count
            "swapi-route-stats 1\nd 2026-10-19 /mcp many\n",            // not a number
            "swapi-route-stats 1\nd 2026-13-40 /mcp 1\n",               // bad date
            "swapi-route-stats 1\nh 2026-10-19T12:30:00Z /mcp 1\n",     // not an hour start
            "swapi-route-stats 1\nsince yesterday\n",                   // bad instant
    })
    void corruptFileIsIgnoredWithAWarning(String content) throws IOException {
        // A known line that is malformed still discards the whole file. Unknown
        // record types and unknown routes are skipped instead (tests below).
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), content, StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), file().read());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("ignored"), warnings.getFirst());
    }

    @Test
    void aSnapshotWithAnUnknownRouteKeepsEveryKnownCounter() throws IOException {
        // Regression: a route a later version counts (here /not-a-route-yet.txt) used to make
        // the whole file corrupt, so a rollback lost every counter.
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 2
                since 2026-10-19T12:30:00Z
                h 2026-10-19T12:00:00Z /api/people/{id} 7
                h 2026-10-19T12:00:00Z /not-a-route-yet.txt 12
                h 2026-10-19T12:00:00Z /mcp 5
                d 2026-10-19 /api/people/{id} 7
                d 2026-10-19 /not-a-route-yet.txt 12
                d 2026-10-19 /mcp 5
                d 2026-10-19 other 1
                th 2026-10-19T12:00:00Z /not-a-route-yet.txt 1 900 810000.0 900 900 17:1
                th 2026-10-19T12:00:00Z /mcp 3 3900 5490000.0 900 1800 17:1,19:1,21:1
                td 2026-10-19 /not-a-route-yet.txt 1 900 810000.0 900 900 17:1
                td 2026-10-19 /mcp 3 3900 5490000.0 900 1800 17:1,19:1,21:1
                """, StandardCharsets.UTF_8);

        assertEquals(Optional.of(sample()), file().read());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("skipped 4"), warnings.getFirst());
        assertFalse(warnings.getFirst().contains("/not-a-route-yet.txt"), warnings.getFirst());
    }

    @Test
    void aSnapshotLineForLlmsTxtIsRestored() throws IOException {
        // /llms.txt is a counted route since 2.9.0: its line is restored, not skipped.
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 2
                since 2026-10-19T12:30:00Z
                d 2026-10-19 /llms.txt 3
                """, StandardCharsets.UTF_8);

        StatsData data = file().read().orElseThrow();
        assertEquals(Map.of(LocalDate.parse("2026-10-19"), Map.of("/llms.txt", 3L)), data.days());
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void anUnknownRecordTypeIsSkipped() throws IOException {
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 2
                since 2026-10-19T12:30:00Z
                d 2026-10-19 /mcp 5
                x 2026-10-19 /mcp 1
                q foo
                h 2026-10-19T12:00:00Z /mcp 5
                """, StandardCharsets.UTF_8);

        StatsData data = file().read().orElseThrow();
        assertEquals(Map.of(LocalDate.parse("2026-10-19"), Map.of("/mcp", 5L)), data.days());
        assertEquals(Map.of(Instant.parse("2026-10-19T12:00:00Z"), Map.of("/mcp", 5L)), data.hours());
        assertEquals(Instant.parse("2026-10-19T12:30:00Z"), data.countingSince());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("skipped 2"), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("first at line 4"), warnings.getFirst());
    }

    @Test
    void timesInAFormatOneFileAreSkippedNotFatal() throws IOException {
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 1
                d 2026-10-19 /mcp 5
                td 2026-10-19 /mcp 1 900 810000.0 900 900 17:1
                """, StandardCharsets.UTF_8);

        StatsData data = file().read().orElseThrow();
        assertEquals(Map.of(LocalDate.parse("2026-10-19"), Map.of("/mcp", 5L)), data.days());
        assertTrue(data.hourTimes().isEmpty());
        assertTrue(data.dayTimes().isEmpty());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("skipped 1"), warnings.getFirst());
    }

    @Test
    void skippedLinesNeverReachTheData() throws IOException {
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 2
                h 2026-10-19T12:00:00Z /api/people/1 3
                d 2026-10-19 /api/people/1 3
                d 2026-10-19 203.0.113.7 1
                d 2026-10-19 /mcp 2
                td 2026-10-19 /api/people/1 1 900 810000.0 900 900 17:1
                th 2026-10-19T12:00:00Z 203.0.113.7 1 900 810000.0 900 900 17:1
                """, StandardCharsets.UTF_8);

        StatsData data = file().read().orElseThrow();
        List<String> keys = new ArrayList<>();
        data.hours().values().forEach(m -> keys.addAll(m.keySet()));
        data.days().values().forEach(m -> keys.addAll(m.keySet()));
        data.hourTimes().values().forEach(m -> keys.addAll(m.keySet()));
        data.dayTimes().values().forEach(m -> keys.addAll(m.keySet()));
        assertEquals(List.of("/mcp"), keys);
        for (String key : keys) {
            assertEquals(Optional.of(key), RouteNormalizer.normalize(key), key);
        }
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("skipped 5"), warnings.getFirst());
        assertFalse(warnings.getFirst().contains("/api/people/1"), warnings.getFirst());
        assertFalse(warnings.getFirst().contains("203.0.113.7"), warnings.getFirst());
    }

    @Test
    void aFileOfOnlyUnknownLinesRestoresNothingWithoutBeingCorrupt() throws IOException {
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), """
                swapi-route-stats 2
                since 2026-10-19T12:30:00Z
                d 2026-10-19 /not-a-route-yet.txt 3
                x something new
                """, StandardCharsets.UTF_8);

        assertEquals(Optional.of(new StatsData(Instant.parse("2026-10-19T12:30:00Z"), Map.of(), Map.of())),
                file().read());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("skipped"), warnings.getFirst());
        assertFalse(warnings.getFirst().contains("corrupt"), warnings.getFirst());
    }

    @Test
    void binaryGarbageIsIgnoredWithAWarning() throws IOException {
        Files.write(dir.resolve(SnapshotFile.FILE_NAME), new byte[]{(byte) 0xff, 0x00, (byte) 0xfe, 0x01});

        assertEquals(Optional.empty(), file().read());
        assertEquals(1, warnings.size());
    }

    @Test
    void oversizedFileIsNotEvenRead() throws IOException {
        Files.write(dir.resolve(SnapshotFile.FILE_NAME), new byte[(int) SnapshotFile.MAX_BYTES + 1]);

        assertEquals(Optional.empty(), file().read());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("larger than"));
    }

    @Test
    void encodedSnapshotNeverCarriesAnythingButTemplates() {
        String text = SnapshotFile.encode(sample());
        assertFalse(text.contains("/api/people/1 "));
        for (String line : text.split("\n")) {
            assertTrue(line.matches("swapi-route-stats 2|since \\S+|[hd] \\S+ \\S+ \\d+"
                    + "|t[hd] \\S+ \\S+ \\d+ \\d+ [0-9.E]+ \\d+ \\d+ \\d+:\\d+(,\\d+:\\d+)*"), line);
        }
    }
}
