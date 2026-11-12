package com.eldermoraes.mcp;

import java.util.function.BooleanSupplier;

/**
 * Polls a condition until it holds or a deadline passes, for what the MCP
 * extension does after the HTTP answer is out (the idle sweep, the removal of
 * an auto-init session). Shared by the tests that read the session heap.
 */
final class Within {

    private Within() {
    }

    /** True as soon as the condition holds; false if it still fails after the deadline. */
    static boolean within(long millis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
