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
    public void toolsAreListedAsReadOnly() {
        client().when()
                .toolsList(page -> {
                    assertEquals(4, page.size());
                    page.findByName("sw_list").annotations().ifPresentOrElse(
                            a -> assertTrue(a.readOnlyHint()),
                            () -> fail("sw_list sem annotations"));
                    assertNotNull(page.findByName("sw_get"));
                    assertNotNull(page.findByName("sw_random"));
                    assertNotNull(page.findByName("sw_search"));
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
}
