package com.eldermoraes.mcp;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One line per kind per minute, whatever the number of malformed requests. */
class OncePerMinuteTest {

    private final AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(5));
    private final OncePerMinute warnings = new OncePerMinute(Logger.getLogger(OncePerMinuteTest.class), now::get);

    @Test
    void oneLinePerKindPerMinute() {
        assertTrue(warnings.shouldLog("params"));
        for (int i = 0; i < 1000; i++) {
            assertFalse(warnings.shouldLog("params"));
        }
        // Another kind is not held back by the first.
        assertTrue(warnings.shouldLog("_meta"));
        now.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertFalse(warnings.shouldLog("params"));
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(warnings.shouldLog("params"));
        assertFalse(warnings.shouldLog("params"));
    }

    // The kinds come from the code, but should a caller ever pass something
    // unbounded, the map stops growing and the rest share one line a minute.
    @Test
    void theKindsAreBounded() {
        for (int i = 0; i < OncePerMinute.MAX_KINDS; i++) {
            assertTrue(warnings.shouldLog("kind " + i));
        }
        assertTrue(warnings.shouldLog("one more"));
        assertFalse(warnings.shouldLog("and another"));
        assertFalse(warnings.shouldLog("kind 0"));
    }
}
