package com.eldermoraes.mcp;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext.ContextState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only observer for McpRequestContextTest: keeps every request context
 * the application starts while {@link #recording} is on, with the thread and
 * the stack that started it.
 *
 * The @Initialized event fires right after ArC makes the new state current,
 * so getState() here is the context that just started. Whether it ended is
 * read from the state itself (isValid() turns false when it is destroyed),
 * not from counting @Destroyed events: a count cannot tell the contexts of
 * the test's tool calls from one that another request started or ended
 * inside the same window.
 */
@ApplicationScoped
public class RequestContextCounter {

    static final AtomicLong INITIALIZED = new AtomicLong();
    static final AtomicLong DESTROYED = new AtomicLong();

    static volatile boolean recording;
    static final Map<ContextState, String> STARTED = new ConcurrentHashMap<>();

    void onInitialized(@Observes @Initialized(RequestScoped.class) Object event) {
        INITIALIZED.incrementAndGet();
        if (recording) {
            ContextState state = Arc.container().requestContext().getState();
            if (state != null) {
                STARTED.put(state, origin());
            }
        }
    }

    void onDestroyed(@Observes @Destroyed(RequestScoped.class) Object event) {
        DESTROYED.incrementAndGet();
    }

    private static String origin() {
        StringBuilder sb = new StringBuilder(Thread.currentThread().getName());
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 40); i++) {
            sb.append("\n    at ").append(stack[i]);
        }
        return sb.toString();
    }
}
