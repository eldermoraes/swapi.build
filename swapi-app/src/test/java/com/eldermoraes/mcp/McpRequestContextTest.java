package com.eldermoraes.mcp;

import io.quarkus.arc.InjectableContext.ContextState;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 */
@QuarkusTest
class McpRequestContextTest {

    private static final int CALLS = 20;

    @Test
    void requestContextIsDestroyedAfterEachToolCall() throws Exception {
        RequestContextCounter.STARTED.clear();
        RequestContextCounter.recording = true;
        try {
            callTools();
        } finally {
            RequestContextCounter.recording = false;
        }
        Map<ContextState, String> started = Map.copyOf(RequestContextCounter.STARTED);
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
