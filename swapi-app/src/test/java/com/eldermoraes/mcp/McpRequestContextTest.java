package com.eldermoraes.mcp;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext.ContextState;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ThreadContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * quarkus-mcp-server PR #1060 (fixed in 2.0.3): the request context of a tool
 * call was never destroyed. SwapiTools injects a request-scoped
 * HttpServerRequest, so every leaked context would keep it alive.
 *
 * Each context started during the calls is followed by its own state
 * (RequestContextCounter), not by comparing global counts of @Initialized and
 * @Destroyed: on CI (PR #38, run 40589203591) the global count read 41
 * started and 40 destroyed after a 3 s wait, a context the count could not
 * name. Here a context still alive after 10 s fails with the thread and the
 * stack that started it. On 2.0.0: "20 of 40 ... never destroyed".
 *
 * Run 40592856066 then named the other kind: contexts started by context
 * propagation when it restores one that died meanwhile (4 of 44 on CI). Those
 * are counted apart and must hold no beans.
 */
@QuarkusTest
class McpRequestContextTest {

    private static final int CALLS = 20;

    // The frame that starts a context when context propagation restores one
    // that died meanwhile (contextPropagationRestoresAFreshContextWhenTheCapturedOneDied).
    private static final String RESTORE = "ArcContextProvider$RestoreContextController.endContext";

    @Inject
    ThreadContext threadContext;

    @Test
    void requestContextIsDestroyedAfterEachToolCall() throws Exception {
        RequestContextCounter.STARTED.clear();
        RequestContextCounter.recording = true;
        try {
            callTools();
        } finally {
            RequestContextCounter.recording = false;
        }
        Map<ContextState, String> all = Map.copyOf(RequestContextCounter.STARTED);
        Map<ContextState, String> started = new HashMap<>();
        Map<ContextState, String> restored = new HashMap<>();
        all.forEach((state, origin) -> (origin.contains(RESTORE) ? restored : started).put(state, origin));
        assertTrue(started.size() >= CALLS, "each tool call starts a request context: " + started.size());
        // The extension ends its context after the response is written, on
        // the Vert.x context of the call: wait for each one, with a deadline.
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> alive = alive(started);
        while (!alive.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            alive = alive(started);
        }
        assertEquals(List.of(), alive, alive.size() + " of " + started.size()
                + " request contexts started by the tool calls were never destroyed");
        // The other kind (see contextPropagationRestoresAFreshContextWhenTheCapturedOneDied):
        // never destroyed, but empty. Nothing in it to destroy, and it lives in
        // the duplicated Vert.x context of the call, which is dropped after it.
        for (Map.Entry<ContextState, String> e : restored.entrySet()) {
            assertTrue(e.getKey().getContextualInstances().isEmpty(),
                    "a context restored by context propagation holds beans: "
                            + e.getKey().getContextualInstances().keySet() + "\n" + e.getValue());
        }
    }

    // What CI hit twice (runs 40589203591 and 40592856066): sw_get runs on a
    // worker thread (FeatureManagerBase.execute, executeBlocking) and its Uni is
    // subscribed with a consumer that SmallRye Context Propagation wraps. The
    // consumer completes the call; the event loop writes the answer and the
    // extension destroys the call's context there (McpRequestImpl.contextEnd).
    // When that wins the race against the worker thread, the wrapper, on
    // leaving the consumer, restores a state that is no longer valid, and
    // ArcContextProvider.RestoreContextController.endContext then activates
    // a NEW, empty request context (activate(null)) that nobody destroys.
    // Same mechanism here, without the race: the captured context dies inside
    // the consumer.
    @Test
    void contextPropagationRestoresAFreshContextWhenTheCapturedOneDied() {
        ManagedContext requestContext = Arc.container().requestContext();
        ContextState call = requestContext.activate();
        try {
            Consumer<Object> wrapped = threadContext.contextualConsumer(item -> requestContext.destroy(call));
            wrapped.accept("result");
            assertFalse(call.isValid(), "the call's context was destroyed inside the consumer");
            assertTrue(requestContext.isActive(), "context propagation left a request context active");
            ContextState fresh = requestContext.getState();
            assertNotSame(call, fresh);
            assertTrue(fresh.isValid());
            assertTrue(fresh.getContextualInstances().isEmpty(), "the restored context starts empty");
        } finally {
            requestContext.terminate();
        }
    }

    private static List<String> alive(Map<ContextState, String> started) {
        return started.entrySet().stream().filter(e -> e.getKey().isValid()).map(Map.Entry::getValue).toList();
    }

    private static void callTools() {
        for (int i = 0; i < CALLS; i++) {
            given()
                    .contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .body("""
                            {"jsonrpc":"2.0","id":%d,"method":"tools/call",
                             "params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}}}
                            """.formatted(i))
            .when()
                    .post("/mcp")
            .then()
                    .statusCode(200)
                    .body(containsString("Luke Skywalker"));
        }
    }
}
