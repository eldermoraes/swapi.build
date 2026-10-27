package com.eldermoraes.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only observer for McpRequestContextTest: counts every request context
 * the application starts and ends.
 */
@ApplicationScoped
public class RequestContextCounter {

    static final AtomicLong INITIALIZED = new AtomicLong();
    static final AtomicLong DESTROYED = new AtomicLong();

    void onInitialized(@Observes @Initialized(RequestScoped.class) Object event) {
        INITIALIZED.incrementAndGet();
    }

    void onDestroyed(@Observes @Destroyed(RequestScoped.class) Object event) {
        DESTROYED.incrementAndGet();
    }
}
