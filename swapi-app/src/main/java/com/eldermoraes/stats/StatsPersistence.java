package com.eldermoraes.stats;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps the usage counters across restarts when the host allows it.
 *
 * The host runs the app with {@code ProtectSystem=strict}: the disk is
 * read-only except for a directory systemd hands over with
 * {@code StateDirectory=swapi} ({@code /var/lib/swapi}), whose path arrives in
 * {@code STATE_DIRECTORY} (docs/DEPLOY.md, "Usage counters"). With it, the
 * counters are restored at startup, saved every 10 minutes when they changed,
 * and saved on shutdown, so a deploy loses at most nothing and a crash at most
 * 10 minutes. Without it, or if it is not writable, the app keeps the counters
 * in memory only and says so once in the log; nothing else changes.
 */
@Singleton
public class StatsPersistence {

    private static final Logger LOG = Logger.getLogger(StatsPersistence.class);

    @Inject
    RouteStats stats;

    /** {@code STATE_DIRECTORY} as systemd sets it; empty in development and tests. */
    @ConfigProperty(name = "swapi.stats.state-dir")
    Optional<String> stateDir;

    Consumer<String> warn = LOG::warn;
    Consumer<String> info = LOG::info;

    private SnapshotFile snapshot;
    private long savedChanges = -1;
    private boolean failing;

    void onStart(@Observes StartupEvent event) {
        start();
    }

    @Scheduled(every = "10m", delayed = "10m", identity = "route-stats-snapshot")
    void periodic() {
        save();
    }

    void onStop(@Observes ShutdownEvent event) {
        save();
    }

    /** Resolves the state directory and restores the last snapshot, if any. */
    synchronized void start() {
        Optional<Path> dir = stateDir.map(String::trim)
                // systemd joins several StateDirectory= entries with ':'; the first one is ours.
                .map(s -> s.split(":", 2)[0])
                .filter(s -> !s.isEmpty())
                .map(Path::of);
        if (dir.isEmpty()) {
            warn.accept("no state directory (STATE_DIRECTORY is not set): usage counters are kept in memory only "
                    + "and restart from zero on every restart");
            return;
        }
        Path path = dir.get();
        if (!Files.isDirectory(path) || !Files.isWritable(path)) {
            warn.accept("state directory " + path + " is missing or not writable: usage counters are kept in "
                    + "memory only and restart from zero on every restart");
            return;
        }
        snapshot = new SnapshotFile(path, warn);
        snapshot.read().ifPresent(data -> {
            stats.restore(data);
            info.accept("usage counters restored from " + snapshot.path() + " (" + data.hours().size()
                    + " hourly and " + data.days().size() + " daily buckets)");
        });
    }

    /** Writes a snapshot if there is a state directory and something changed since the last one. */
    synchronized void save() {
        if (snapshot == null) {
            return;
        }
        long changes = stats.changeCount();
        if (changes == savedChanges) {
            return;
        }
        try {
            snapshot.write(stats.export());
            savedChanges = changes;
            if (failing) {
                info.accept("usage counters snapshot written again to " + snapshot.path());
                failing = false;
            }
        } catch (IOException | RuntimeException e) {
            // One warning per failure streak, not one every 10 minutes.
            if (!failing) {
                warn.accept("usage counters snapshot could not be written to " + snapshot.path() + " ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + "); keeping them in memory");
                failing = true;
            }
        }
    }

    /** True when the counters survive a restart. */
    boolean persistent() {
        return snapshot != null;
    }
}
