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

    private static StatsData sample() {
        return new StatsData(Instant.parse("2026-10-19T12:30:00Z"),
                Map.of(Instant.parse("2026-10-19T12:00:00Z"), Map.of("/api/people/{id}", 7L, "/mcp", 5L)),
                Map.of(LocalDate.parse("2026-10-19"), Map.of("/api/people/{id}", 7L, "/mcp", 5L, "other", 1L)));
    }

    @Test
    void roundTrips() throws IOException {
        file().write(sample());

        assertEquals(Optional.of(sample()), file().read());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void fileHoldsOnlyRoutesBucketsAndCounts() throws IOException {
        file().write(sample());

        assertEquals("""
                swapi-route-stats 1
                since 2026-10-19T12:30:00Z
                h 2026-10-19T12:00:00Z /api/people/{id} 7
                h 2026-10-19T12:00:00Z /mcp 5
                d 2026-10-19 /api/people/{id} 7
                d 2026-10-19 /mcp 5
                d 2026-10-19 other 1
                """, Files.readString(dir.resolve(SnapshotFile.FILE_NAME)));
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
            "swapi-route-stats 2\nd 2026-10-19 /mcp 1\n",               // unknown version
            "swapi-route-stats 1\nd 2026-10-19 /mcp\n",                 // missing field
            "swapi-route-stats 1\nd 2026-10-19 /mcp -1\n",              // negative count
            "swapi-route-stats 1\nd 2026-10-19 /mcp many\n",            // not a number
            "swapi-route-stats 1\nd 2026-13-40 /mcp 1\n",               // bad date
            "swapi-route-stats 1\nh 2026-10-19T12:30:00Z /mcp 1\n",     // not an hour start
            "swapi-route-stats 1\nd 2026-10-19 /api/people/1 1\n",      // an id, not a template
            "swapi-route-stats 1\nd 2026-10-19 203.0.113.7 1\n",        // anything else
            "swapi-route-stats 1\nx 2026-10-19 /mcp 1\n",               // unknown record
            "swapi-route-stats 1\nsince yesterday\n",                   // bad instant
    })
    void corruptFileIsIgnoredWithAWarning(String content) throws IOException {
        Files.writeString(dir.resolve(SnapshotFile.FILE_NAME), content, StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), file().read());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("ignored"), warnings.getFirst());
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
            assertTrue(line.matches("swapi-route-stats 1|since \\S+|[hd] \\S+ \\S+ \\d+"), line);
        }
    }
}
