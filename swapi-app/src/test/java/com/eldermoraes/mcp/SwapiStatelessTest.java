package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.CacheScope;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
public class SwapiStatelessTest {

    @Test
    public void statelessDiscoveryIncludesFlatRequiredCacheFields() {
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("Mcp-Method", "server/discover")
                .header("MCP-Protocol-Version", "2026-07-28")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{
                          "_meta":{
                            "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                            "io.modelcontextprotocol/clientInfo":{"name":"regression-test","version":"1.0"},
                            "io.modelcontextprotocol/clientCapabilities":{}
                          }
                        }}
                        """)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                // 2.12.1 (extension 2.0.3, quarkus-mcp-server PR #1037): the
                // serverInfo of server/discover moved into _meta, as the
                // 2026-07-28 schema says.
                .body("result._meta.'io.modelcontextprotocol/serverInfo'.name", equalTo("swapi.build"))
                .body("result", not(hasKey("serverInfo")))
                .body("result.ttlMs", equalTo(0))
                .body("result.cacheScope", equalTo("public"))
                .body("result", not(hasKey("cacheControl")));
    }

    @Test
    public void discoverCarriesInstructions() {
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("Mcp-Method", "server/discover")
                .header("MCP-Protocol-Version", "2026-07-28")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{
                          "_meta":{
                            "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                            "io.modelcontextprotocol/clientInfo":{"name":"regression-test","version":"1.0"},
                            "io.modelcontextprotocol/clientCapabilities":{}
                          }
                        }}
                        """)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .body("result.instructions", containsString("sw_search"))
                .body("result.instructions", containsString("not an episode number"));
    }

    @Test
    public void statelessToolListIncludesRequiredCacheFields() {
        var client = McpAssured.newStreamableClient()
                .setStateless()
                .build()
                .connect();
        try {
            client.when()
                    .toolsList(page -> {
                        assertEquals(4, page.size());
                        assertNotNull(page.cacheControl(), "Stateless results require cache fields");
                        assertEquals(0L, page.cacheControl().ttlMs());
                        assertEquals(CacheScope.PUBLIC, page.cacheControl().cacheScope());
                    })
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void statelessClientCallsToolWithoutInitialize() {
        var client = McpAssured.newStreamableClient()
                .setStateless()
                .build()
                .connect();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        assertTrue(r.content().get(0).asText().text().contains("Luke Skywalker"));
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
