package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;

/**
 * A sessao MCP vive na heap de uma instancia e o Vercel nao tem afinidade de
 * sessao: um Mcp-Session-Id emitido pela instancia A chega na instancia B, que
 * nao o conhece. Sem auto-init isso e 404 e o cliente stateful quebra.
 *
 * Estes testes usam rest-assured, nao McpAssured, de proposito: o McpAssured
 * gerencia a sessao e por isso nunca reproduz o bug.
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

    // auto-init nao pode suprimir o Mcp-Session-Id: um cliente stateful
    // bem-comportado precisa continuar recebendo sessao e negociando a versao
    // que pediu. Este teste tranca esse comportamento.
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
