package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Edges of the MCP endpoint in this topology.
 *
 * auto-init covers POST, which is where the tool calls happen, but GET and
 * DELETE with a foreign session are still 404. Since we emit no
 * server->client messages, the 405 from the 2025-03-26 spec is the correct
 * and unconditional answer for GET, and DELETE (teardown) always succeeds.
 */
@QuarkusTest
class McpTransportEdgesTest {

    @Test
    void getIsMethodNotAllowedBecauseThereIsNoServerToClientStream() {
        // Allow lists everything the resource actually responds to: POST (tool
        // calls) and DELETE (teardown) - RFC 9110.
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp")
        .then()
                .statusCode(405)
                .header("Allow", equalTo("POST, DELETE"));
    }

    @Test
    void getWithAForeignSessionIdIsAlsoMethodNotAllowed() {
        given()
                .accept("text/event-stream")
                .header("Mcp-Session-Id", "session-issued-by-another-instance")
        .when()
                .get("/mcp")
        .then()
                .statusCode(405);
    }

    @Test
    void deleteIsAlwaysSuccessfulTeardown() {
        given()
                .header("Mcp-Session-Id", "session-issued-by-another-instance")
        .when()
                .delete("/mcp")
        .then()
                .statusCode(204);
    }

    @Test
    void legacySseTransportIsRejectedWithAPointerToStreamableHttp() {
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp/sse")
        .then()
                .statusCode(404)
                .body(containsString("/mcp"));
    }

    @Test
    void legacyMessageEndpointIsRejected() {
        given()
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
        .when()
                .post("/mcp/messages/whatever-id")
        .then()
                .statusCode(404)
                .body(containsString("/mcp"));
    }

    @Test
    void mcpAnswersCorsPreflightSoBrowserClientsCanConnect() {
        given()
                .header("Origin", "https://app.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,mcp-session-id")
        .when()
                .options("/mcp")
        .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", containsString("app.example"));
    }

    // The filter ends the response, so it must run AFTER the CORS handler
    // (priority 300) - otherwise a browser client receives the 405 without
    // Access-Control-Allow-Origin and sees a CORS error instead of the 405.
    @Test
    void methodNotAllowedStillCarriesCorsHeadersForBrowserClients() {
        given()
                .header("Origin", "https://app.example")
                .accept("text/event-stream")
        .when()
                .get("/mcp")
        .then()
                .statusCode(405)
                .header("Access-Control-Allow-Origin", containsString("app.example"));
    }

    // The Vert.x router ignores a trailing slash when matching an exact path;
    // without normalizing it in the filter, a client configured with /mcp/
    // slipped through and hit the ambiguous 404 this filter exists to eliminate.
    @Test
    void getWithTrailingSlashIsAlsoMethodNotAllowed() {
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp/")
        .then()
                .statusCode(405);
    }

    @Test
    void deleteWithTrailingSlashIsAlsoSuccessfulTeardown() {
        given()
        .when()
                .delete("/mcp/")
        .then()
                .statusCode(204);
    }

    @Test
    void legacySseTransportWithTrailingSlashIsAlsoRejected() {
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp/sse/")
        .then()
                .statusCode(404)
                .body(containsString("/mcp"));
    }
}
