package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Bordas do endpoint MCP nesta topologia.
 *
 * O auto-init cobre o POST, que e onde as tool calls acontecem, mas GET e
 * DELETE com sessao estrangeira continuam 404. Como nao emitimos nenhuma
 * mensagem server->client, o 405 da spec 2025-03-26 e a resposta correta e
 * incondicional para o GET, e o DELETE (teardown) e sempre bem-sucedido.
 */
@QuarkusTest
class McpTransportEdgesTest {

    @Test
    void getIsMethodNotAllowedBecauseThereIsNoServerToClientStream() {
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp")
        .then()
                .statusCode(405)
                .header("Allow", containsString("POST"));
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
}
