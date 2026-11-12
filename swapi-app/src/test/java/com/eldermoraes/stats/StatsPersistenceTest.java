package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    void unknownLinesAreDroppedOnTheNextSave() throws IOException {
        // What a rollback finds: a snapshot from a later version that counts a route
        // this one does not know. The known counters come back; the unknown line
        // does not survive the next save.
        Path file = dir.resolve(SnapshotFile.FILE_NAME);
        Files.writeString(file, """
                swapi-route-stats 2
                since 2026-10-19T12:00:00Z
                h 2026-10-19T12:00:00Z /api/films 2
                d 2026-10-19 /api/films 2
                d 2026-10-19 /not-a-route-yet.txt 3
                """);
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.toString()));
        p.start();

        assertEquals(Map.of("/api/films", 2L), stats.day(LocalDate.parse("2026-10-19")));
        assertTrue(infos.getLast().contains("restored"), infos::toString);
        assertEquals(1, warnings.size(), warnings::toString);

        stats.record("/mcp");
        p.save();
        String saved = Files.readString(file);
        assertFalse(saved.contains("/not-a-route-yet.txt"), saved);
        assertTrue(saved.contains("d 2026-10-19 /api/films 2"), saved);
        assertTrue(saved.contains("d 2026-10-19 /mcp 1"), saved);
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

    // #60: the size limit was checked only when reading, so the app could write,
    // in silence, a file the next start would refuse.
    @Test
    void snapshotLargerThanTheReadLimitIsWrittenAndReportedOncePerRun() throws IOException {
        RouteStats before = new RouteStats(clock);
        StatsPersistence first = persistence(before, Optional.of(dir.toString()));
        first.snapshotLimit = 120;                           // one route fits (97 bytes), two do not (159)
        first.start();
        before.record("/mcp");
        before.record("/api/films");
        first.save();

        Path file = dir.resolve(SnapshotFile.FILE_NAME);
        assertTrue(Files.readString(file).contains("d 2026-10-19 /api/films 1"), "written in full all the same");
        assertEquals(1, warnings.size(), warnings::toString);
        String warning = warnings.getFirst();
        assertTrue(warning.contains(file.toString()), warning);
        assertTrue(warning.contains(Files.size(file) + " bytes"), warning);
        assertTrue(warning.contains("120 bytes"), warning);
        assertFalse(warning.contains("/api/films"), "sizes only, never what was counted: " + warning);

        before.record("/mcp");
        first.save();                                        // still too large: the same run
        assertEquals(1, warnings.size(), warnings::toString);

        // What the warning announces: the next process refuses the file.
        RouteStats after = new RouteStats(clock);
        StatsPersistence second = persistence(after, Optional.of(dir.toString()));
        second.snapshotLimit = 120;
        second.start();
        assertTrue(after.day(LocalDate.parse("2026-10-19")).isEmpty());
        assertEquals(2, warnings.size(), warnings::toString);
        assertTrue(warnings.getLast().contains("larger than 120 bytes"), warnings::toString);
    }

    @Test
    void snapshotThatFitsAgainEndsTheRunAndTheNextOneWarnsAgain() throws IOException {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.toString()));
        p.snapshotLimit = 120;
        p.start();
        stats.record("/mcp");
        stats.record("/api/films");
        p.save();
        assertEquals(1, warnings.size(), warnings::toString);

        clock.advance(Duration.ofDays(RouteStats.RETAINED_DAYS + 1));   // both buckets age out
        stats.record("/mcp");
        p.save();
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(infos.getLast().contains("fits again"), infos::toString);
        assertTrue(Files.size(dir.resolve(SnapshotFile.FILE_NAME)) <= 120);

        stats.record("/api/films");
        p.save();
        assertEquals(2, warnings.size(), "a new run of oversized snapshots: " + warnings);
    }

    @Test
    void snapshotWithinTheReadLimitIsNotReported() {
        RouteStats stats = new RouteStats(clock);
        StatsPersistence p = persistence(stats, Optional.of(dir.toString()));
        p.snapshotLimit = 120;
        p.start();
        stats.record("/mcp");
        p.save();

        assertTrue(warnings.isEmpty(), warnings::toString);
        assertTrue(infos.isEmpty(), infos::toString);
    }
}
