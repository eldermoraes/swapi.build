package com.eldermoraes.mcp;

import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * At most one WARN line per kind per minute, without a stack trace. A client
 * with a defect, or someone sending malformed requests on purpose, must not
 * fill the journal with a line per request. The kinds are fixed labels chosen
 * by the code (never text from the request), so the map stays small; past
 * MAX_KINDS every new kind shares one entry.
 */
final class OncePerMinute {

    static final int MAX_KINDS = 64;
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final String OTHER = "other";

    private final Logger log;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    OncePerMinute(Logger log) {
        this(log, System::nanoTime);
    }

    OncePerMinute(Logger log, LongSupplier clock) {
        this.log = log;
        this.clock = clock;
    }

    /** Logs the line, which is its own kind, unless it went out in the last minute. */
    void warn(String line) {
        if (shouldLog(line)) {
            log.warnf("%s (at most one line of this kind per minute)", line);
        }
    }

    boolean shouldLog(String kind) {
        String key = lastLogged.size() >= MAX_KINDS && !lastLogged.containsKey(kind) ? OTHER : kind;
        long now = clock.getAsLong();
        boolean[] log = new boolean[1];
        lastLogged.compute(key, (k, last) -> {
            if (last == null || now - last >= WINDOW_NANOS) {
                log[0] = true;
                return now;
            }
            return last;
        });
        return log[0];
    }
}
