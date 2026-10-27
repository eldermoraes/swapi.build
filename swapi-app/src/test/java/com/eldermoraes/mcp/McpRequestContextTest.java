package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * quarkus-mcp-server PR #1060 (fixed in 2.0.3): the request context of a tool
 * call was never destroyed. SwapiTools injects a request-scoped
 * HttpServerRequest, so every leaked context would keep it alive.
 */
@QuarkusTest
class McpRequestContextTest {

    private static final int CALLS = 20;

    @Test
    void requestContextIsDestroyedAfterEachToolCall() throws Exception {
        long initializedBefore = RequestContextCounter.INITIALIZED.get();
        long destroyedBefore = RequestContextCounter.DESTROYED.get();
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
        // The context ends after the response is written; give it a moment.
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline
                && RequestContextCounter.INITIALIZED.get() - initializedBefore
                        != RequestContextCounter.DESTROYED.get() - destroyedBefore) {
            Thread.sleep(50);
        }
        long initialized = RequestContextCounter.INITIALIZED.get() - initializedBefore;
        long destroyed = RequestContextCounter.DESTROYED.get() - destroyedBefore;
        assertTrue(initialized >= CALLS, "each tool call starts a request context: " + initialized);
        assertEquals(initialized, destroyed, "request contexts started but never destroyed");
    }
}
