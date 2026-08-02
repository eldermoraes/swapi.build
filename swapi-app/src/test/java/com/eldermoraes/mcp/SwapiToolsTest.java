package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SwapiToolsTest {

    static McpStreamableTestClient client;

    static McpStreamableTestClient client() {
        if (client == null) {
            client = McpAssured.newConnectedStreamableClient();
        }
        return client;
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.disconnect();
        }
    }

    @Test
    public void allToolsAdvertiseReadOnlyNonDestructiveHints() {
        client().when()
                .toolsList(page -> {
                    assertEquals(4, page.size());
                    for (String name : java.util.List.of("sw_list", "sw_get", "sw_random", "sw_search")) {
                        var tool = page.findByName(name);
                        assertNotNull(tool, name + " ausente");
                        tool.annotations().ifPresentOrElse(a -> {
                            assertTrue(a.readOnlyHint(), name + " deveria ser readOnly");
                            assertFalse(a.destructiveHint(), name + " nao deveria anunciar destructive");
                            assertFalse(a.openWorldHint(), name + " nao deveria anunciar openWorld");
                        }, () -> fail(name + " sem annotations"));
                    }
                })
                .thenAssertResults();
    }

    @Test
    public void getReturnsLukeById() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertTrue(r.content().get(0).asText().text().contains("Luke Skywalker"));
                    assertTrue(r.content().get(0).asText().text()
                            .contains("\"homeworld\":\"http://localhost:8081/api/planets/1\""));
                })
                .send()
                .thenAssertResults();
    }

    @Test
    public void searchFindsSkywalkers() {
        client().when()
                .toolsCall("sw_search")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "query", "skywalker"))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertTrue(r.content().get(0).asText().text().toLowerCase().contains("skywalker"));
                })
                .send()
                .thenAssertResults();
    }

    @Test
    public void unknownIdIsToolError() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 99999))
                .withAssert(r -> assertTrue(r.isError()))
                .send()
                .thenAssertResults();
    }

    @Test
    public void getFilmByRecordIdReturnsANewHope() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "FILMS", "id", 1))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertTrue(r.content().get(0).asText().text().contains("A New Hope"));
                })
                .send()
                .thenAssertResults();
    }

    @Test
    public void unknownFilmIdIsToolError() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "FILMS", "id", 9999))
                .withAssert(r -> assertTrue(r.isError()))
                .send()
                .thenAssertResults();
    }
}
