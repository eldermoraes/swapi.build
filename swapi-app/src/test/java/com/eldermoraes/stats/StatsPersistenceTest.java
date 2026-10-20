package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

class StatsPersistenceTest {

    @TempDir
    Path dir;

    private final RouteStatsTest.MutableClock clock =
            new RouteStatsTest.MutableClock(Instant.parse("2026-10-19T12:30:00Z"));
    private final List<String> warnings = new ArrayList<>();
    private final List<String> infos = new ArrayList<>();

    private StatsPersistence persistence(RouteStats stats, Optional<String> stateDir) {
        StatsPersistence p = new StatsPersistence();
        p.stats = stats;
        p.stateDir = stateDir;
        p.warn = warnings::add;
        p.info = infos::add;
        return p;
    }

    @Test
    void countersSurviveARestart() {
        RouteStats before = new RouteStats(clock);
        StatsPersistence first = persistence(before, Optional.of(dir.toString()));
        first.start();
        before.record("/mcp");
        before.record("/api/films");
        first.save();                                        // shutdown

        RouteStats after = new RouteStats(clock);
        StatsPersistence second = persistence(after, Optional.of(dir.toString()));
        second.start();                                      // next process

        assertEquals(Map.of("/mcp", 1L, "/api/films", 1L), after.day(LocalDate.parse("2026-10-19")));
        assertEquals(Map.of("/mcp", 1L, "/api/films", 1L), after.hour(Instant.parse("2026-10-19T12:00:00Z")));
        assertTrue(warnings.isEmpty(), warnings::toString);
        assertTrue(infos.getLast().contains("restored"), infos::toString);
    }

    @Test
    void withoutAStateDirectoryCountersStayInMemoryWithOneWarning() {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.empty());
        p.start();
        stats.record("/mcp");
        p.save();
        p.save();
        p.periodic();

        assertFalse(p.persistent());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("in memory only"));
        assertEquals(1L, stats.day(LocalDate.parse("2026-10-19")).get("/mcp"));
    }

    @Test
    void blankStateDirectoryIsTheSameAsNone() {
        StatsPersistence p = persistence(new RouteStats(clock), Optional.of("  "));
        p.start();

        assertFalse(p.persistent());
        assertEquals(1, warnings.size());
    }

    @Test
    void missingStateDirectoryFallsBackToMemoryWithOneWarning() {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.resolve("absent").toString()));
        p.start();
        stats.record("/mcp");
        p.save();

        assertFalse(p.persistent());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("missing or not writable"));
        assertFalse(Files.exists(dir.resolve("absent")));
    }

    @Test
    void firstOfSeveralSystemdStateDirectoriesIsUsed() {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir + ":/var/lib/elsewhere"));
        p.start();
        stats.record("/mcp");
        p.save();

        assertTrue(Files.exists(dir.resolve(SnapshotFile.FILE_NAME)));
    }

    @Test
    void corruptSnapshotIsIgnoredAndReplacedOnTheNextSave() throws IOException {
        Path file = dir.resolve(SnapshotFile.FILE_NAME);
        Files.writeString(file, "not a snapshot");
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.toString()));
        p.start();

        assertTrue(p.persistent(), "a bad file does not cost the state directory");
        assertEquals(1, warnings.size());
        assertTrue(stats.day(LocalDate.parse("2026-10-19")).isEmpty());

        stats.record("/mcp");
        p.save();
        assertTrue(Files.readString(file).startsWith(SnapshotFile.HEADER));
    }

    @Test
    void unchangedCountersAreNotRewritten() throws IOException {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.toString()));
        p.start();
        stats.record("/mcp");
        p.save();
        Path file = dir.resolve(SnapshotFile.FILE_NAME);
        Files.writeString(file, Files.readString(file) + "\n");   // marker

        p.save();
        assertTrue(Files.readString(file).endsWith("\n\n"), "nothing changed, nothing written");

        stats.record("/mcp");
        p.save();
        assertFalse(Files.readString(file).endsWith("\n\n"));
    }

    @Test
    void writeFailureWarnsOncePerStreak() throws IOException {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.toString()));
        p.start();
        // A directory where the temporary file should go makes every write fail.
        Path blocker = dir.resolve(SnapshotFile.FILE_NAME + ".tmp");
        Files.createDirectory(blocker);
        stats.record("/mcp");
        p.save();
        stats.record("/mcp");
        p.save();
        assertEquals(1, warnings.size(), warnings::toString);

        Files.delete(blocker);
        stats.record("/mcp");
        p.save();
        assertTrue(Files.exists(dir.resolve(SnapshotFile.FILE_NAME)));
        assertTrue(infos.getLast().contains("written again"));
    }
}
