package com.eldermoraes.stats;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The counters on disk: one small text file in the state directory.
 *
 * <pre>
 * swapi-route-stats 2
 * since 2026-10-19T12:00:00Z
 * h 2026-10-19T12:00:00Z /api/people/{id} 7
 * d 2026-10-19 /api/people/{id} 7
 * th 2026-10-19T12:00:00Z /api/people/{id} 7 8400 1.0E7 900 1800 21:3,22:2,24:2
 * td 2026-10-19 /api/people/{id} 7 8400 1.0E7 900 1800 21:3,22:2,24:2
 * </pre>
 *
 * {@code h} and {@code d} are counts per hour and per day. {@code th} and
 * {@code td} (format 2, release 2.8.0) are server times per hour and per day:
 * timed requests, sum of the times, sum of their squares, minimum and maximum
 * (microseconds), then the non-empty {@link LatencyHistogram} buckets as
 * {@code index:count}. Format 1 (2.7.0) has no time lines and still loads: its
 * counts come back and the times start empty.
 *
 * Only route templates, bucket starts, counts and times — the same things
 * {@code GET /api/stats} publishes — so the file holds nothing about any client.
 *
 * Reading is strict: a missing header, an unknown line, a route that
 * {@link RouteNormalizer} would not produce, a negative number, time numbers
 * that do not add up, or a file larger than {@link #MAX_BYTES} makes the whole
 * file count as corrupt. It is then ignored with one warning and the next
 * snapshot replaces it; the app never fails to start because of it.
 *
 * Writing is atomic: the snapshot goes to a temporary file in the same
 * directory, is flushed to disk, and is renamed over the previous one, so a
 * crash mid-write leaves the previous snapshot intact.
 */
public final class SnapshotFile {

    static final String FILE_NAME = "route-stats.txt";
    static final String HEADER_V1 = "swapi-route-stats 1";
    static final String HEADER = "swapi-route-stats 2";

    /**
     * Far above the largest legitimate snapshot: with all 45 routes busy every
     * retained hour and day, counts and times come to about 6 MB; in practice
     * the file is a few tens of KB.
     */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private final Path file;
    private final Path temp;
    private final Consumer<String> warn;

    /**
     * @param directory the state directory (it must already exist)
     * @param warn      where warnings go (the log in production, a list in tests)
     */
    public SnapshotFile(Path directory, Consumer<String> warn) {
        this.file = directory.resolve(FILE_NAME);
        this.temp = directory.resolve(FILE_NAME + ".tmp");
        this.warn = warn;
    }

    Path path() {
        return file;
    }

    /** The saved counters, or empty when there is no file or it is corrupt (with a warning). */
    public Optional<StatsData> read() {
        String text;
        try {
            if (Files.size(file) > MAX_BYTES) {
                warn.accept("usage counters snapshot " + file + " is larger than " + MAX_BYTES
                        + " bytes; ignored, starting from zero");
                return Optional.empty();
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            warn.accept("usage counters snapshot " + file + " could not be read (" + e.getClass().getSimpleName()
                    + "); ignored, starting from zero");
            return Optional.empty();
        }
        try {
            return Optional.of(decode(text));
        } catch (IllegalArgumentException e) {
            warn.accept("usage counters snapshot " + file + " is corrupt (" + e.getMessage()
                    + "); ignored, starting from zero");
            return Optional.empty();
        }
    }

    /** Writes the counters atomically. */
    public void write(StatsData data) throws IOException {
        byte[] bytes = encode(data).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String encode(StatsData data) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        if (data.countingSince() != null) {
            out.append("since ").append(data.countingSince()).append('\n');
        }
        new TreeMap<>(data.hours()).forEach((start, counts) -> new TreeMap<>(counts).forEach((route, n) ->
                out.append("h ").append(start).append(' ').append(route).append(' ').append(n).append('\n')));
        new TreeMap<>(data.days()).forEach((day, counts) -> new TreeMap<>(counts).forEach((route, n) ->
                out.append("d ").append(day).append(' ').append(route).append(' ').append(n).append('\n')));
        new TreeMap<>(data.hourTimes()).forEach((start, times) -> new TreeMap<>(times).forEach((route, t) ->
                timing(out.append("th ").append(start).append(' ').append(route), t)));
        new TreeMap<>(data.dayTimes()).forEach((day, times) -> new TreeMap<>(times).forEach((route, t) ->
                timing(out.append("td ").append(day).append(' ').append(route), t)));
        return out.toString();
    }

    private static void timing(StringBuilder out, TimingData t) {
        out.append(' ').append(t.count()).append(' ').append(t.sumMicros()).append(' ').append(t.sumSquares())
                .append(' ').append(t.minMicros()).append(' ').append(t.maxMicros()).append(' ');
        boolean first = true;
        for (int i = 0; i < LatencyHistogram.BUCKETS; i++) {
            long n = t.bucket(i);
            if (n > 0) {
                out.append(first ? "" : ",").append(i).append(':').append(n);
                first = false;
            }
        }
        out.append('\n');
    }

    /** @throws IllegalArgumentException on anything this class did not write */
    static StatsData decode(String text) {
        String[] lines = text.split("\n", -1);
        boolean v2;
        if (lines.length > 0 && HEADER.equals(lines[0])) {
            v2 = true;
        } else if (lines.length > 0 && HEADER_V1.equals(lines[0])) {
            v2 = false;
        } else {
            throw new IllegalArgumentException("missing header");
        }
        Instant since = null;
        Map<Instant, Map<String, Long>> hours = new TreeMap<>();
        Map<LocalDate, Map<String, Long>> days = new TreeMap<>();
        Map<Instant, Map<String, TimingData>> hourTimes = new TreeMap<>();
        Map<LocalDate, Map<String, TimingData>> dayTimes = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(" ", -1);
            try {
                switch (f[0]) {
                    case "since" -> {
                        expect(f, 2, i);
                        since = Instant.parse(f[1]);
                    }
                    case "h" -> {
                        expect(f, 4, i);
                        hours.computeIfAbsent(hourStart(f[1], i), k -> new TreeMap<>())
                                .put(route(f[2], i), count(f[3], i));
                    }
                    case "d" -> {
                        expect(f, 4, i);
                        days.computeIfAbsent(LocalDate.parse(f[1]), k -> new TreeMap<>())
                                .put(route(f[2], i), count(f[3], i));
                    }
                    case "th" -> {
                        if (!v2) {
                            throw new IllegalArgumentException("line " + (i + 1) + ": unknown record");
                        }
                        expect(f, 9, i);
                        hourTimes.computeIfAbsent(hourStart(f[1], i), k -> new TreeMap<>())
                                .put(route(f[2], i), timing(f, i));
                    }
                    case "td" -> {
                        if (!v2) {
                            throw new IllegalArgumentException("line " + (i + 1) + ": unknown record");
                        }
                        expect(f, 9, i);
                        dayTimes.computeIfAbsent(LocalDate.parse(f[1]), k -> new TreeMap<>())
                                .put(route(f[2], i), timing(f, i));
                    }
                    default -> throw new IllegalArgumentException("line " + (i + 1) + ": unknown record");
                }
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": bad date");
            }
        }
        return new StatsData(since, hours, days, hourTimes, dayTimes);
    }

    private static Instant hourStart(String value, int line) {
        Instant start = Instant.parse(value);
        if (start.getEpochSecond() % 3600 != 0) {
            throw new IllegalArgumentException("line " + (line + 1) + ": not an hour start");
        }
        return start;
    }

    /** Fields 3 to 8 of a {@code th}/{@code td} line, checked to add up. */
    private static TimingData timing(String[] f, int line) {
        long n = count(f[3], line);
        long sum = count(f[4], line);
        double squares;
        try {
            squares = Double.parseDouble(f[5]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + (line + 1) + ": bad sum of squares");
        }
        if (!Double.isFinite(squares) || squares < 0 || !f[5].matches("[0-9.E]+")) {
            throw new IllegalArgumentException("line " + (line + 1) + ": bad sum of squares");
        }
        long min = count(f[6], line);
        long max = count(f[7], line);
        if (n == 0 || min > max) {
            throw new IllegalArgumentException("line " + (line + 1) + ": bad timing");
        }
        long[] buckets = new long[LatencyHistogram.BUCKETS];
        long total = 0;
        int previous = -1;
        for (String pair : f[8].split(",", -1)) {
            String[] kv = pair.split(":", -1);
            if (kv.length != 2) {
                throw new IllegalArgumentException("line " + (line + 1) + ": bad histogram");
            }
            int index;
            try {
                index = Integer.parseInt(kv[0]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("line " + (line + 1) + ": bad histogram");
            }
            long c = count(kv[1], line);
            if (index <= previous || index >= LatencyHistogram.BUCKETS || c == 0) {
                throw new IllegalArgumentException("line " + (line + 1) + ": bad histogram");
            }
            buckets[index] = c;
            total += c;
            previous = index;
        }
        if (total != n) {
            throw new IllegalArgumentException("line " + (line + 1) + ": histogram does not add up");
        }
        return new TimingData(n, sum, squares, min, max, buckets);
    }

    private static void expect(String[] fields, int n, int line) {
        if (fields.length != n) {
            throw new IllegalArgumentException("line " + (line + 1) + ": expected " + n + " fields");
        }
    }

    /** Only what {@link RouteNormalizer} produces can come back in: the closed set stays closed. */
    private static String route(String route, int line) {
        if (!RouteNormalizer.normalize(route).equals(Optional.of(route))) {
            throw new IllegalArgumentException("line " + (line + 1) + ": unknown route");
        }
        return route;
    }

    private static long count(String value, int line) {
        try {
            long n = Long.parseLong(value);
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + (line + 1) + ": bad count");
        }
    }
}
