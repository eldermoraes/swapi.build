package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;

/**
 * An MCP session lives in one instance's heap and Vercel has no session
 * affinity: an Mcp-Session-Id issued by instance A arrives at instance B,
 * which does not know it. Without auto-init that is a 404 and the stateful
 * client breaks.
 *
 * These tests use rest-assured, not McpAssured, on purpose: McpAssured
 * manages the session and therefore never reproduces the bug.
 */
@QuarkusTest
class McpForeignSessionTest {

    private static final String ACCEPT = "application/json, text/event-stream";

    private static final String TOOLS_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}}}
            """;

    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            """;

    @Test
    void sessionIdFromAnotherInstanceIsAccepted() {
        given()
                .contentType("application/json")
                .accept(ACCEPT)
                .header("Mcp-Session-Id", "session-issued-by-another-instance")
                .body(TOOLS_CALL)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .body(containsString("Luke Skywalker"));
    }

    @Test
    void requestWithoutAnySessionIdIsAccepted() {
        given()
                .contentType("application/json")
                .accept(ACCEPT)
                .body(TOOLS_LIST)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .body(containsString("sw_get"));
    }

    // auto-init must not suppress the Mcp-Session-Id: a well-behaved stateful
    // client must keep receiving a session and negotiating the version it
    // asked for. This test locks down that behavior.
    @Test
    void wellBehavedStatefulClientStillGetsASessionAndTheVersionItAskedFor() {
        given()
                .contentType("application/json")
                .accept(ACCEPT)
                .body("""
                        {"jsonrpc":"2.0","id":3,"method":"initialize",
                         "params":{"protocolVersion":"2025-06-18","capabilities":{},
                                   "clientInfo":{"name":"well-behaved","version":"1.0"}}}
                        """)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .header("Mcp-Session-Id", not(emptyOrNullString()))
                .body(containsString("2025-06-18"));
    }
}
