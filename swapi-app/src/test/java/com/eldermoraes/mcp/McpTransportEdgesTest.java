package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

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
        // Allow lista tudo que o recurso responde de fato: POST (tool calls) e
        // DELETE (teardown) - RFC 9110.
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

    // O filtro encerra a resposta, entao precisa rodar DEPOIS do handler de CORS
    // (prioridade 300) - senao um cliente de browser recebe o 405 sem
    // Access-Control-Allow-Origin e ve um erro de CORS em vez do 405.
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

    // O router do Vert.x ignora uma barra final ao casar path exato; sem
    // normalizar isso no filtro, um cliente configurado com /mcp/ escapava e
    // caia no 404 ambiguo que este filtro existe para eliminar.
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
