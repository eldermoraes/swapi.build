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
                    // Older protocols do not define the stateless cache fields.
                    assertNull(page.cacheControl());
                    for (String name : java.util.List.of("sw_list", "sw_get", "sw_random", "sw_search")) {
                        var tool = page.findByName(name);
                        assertNotNull(tool, name + " missing");
                        tool.annotations().ifPresentOrElse(a -> {
                            assertTrue(a.readOnlyHint(), name + " should be readOnly");
                            assertFalse(a.destructiveHint(), name + " should not advertise destructive");
                            assertFalse(a.openWorldHint(), name + " should not advertise openWorld");
                        }, () -> fail(name + " has no annotations"));
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

    // The instructions cover only what the tool descriptions cannot: scope, the
    // order between tools and the id trap. No URL: the domain is per request.
    @Test
    public void initializeAdvertisesInstructions() {
        String instructions = client().initResult().instructions();
        assertNotNull(instructions, "initialize carries no instructions");
        assertTrue(instructions.contains("sw_search"), instructions);
        assertTrue(instructions.contains("sw_get"), instructions);
        assertTrue(instructions.contains("the six films of Episodes I-VI"), instructions);
        // Episodes I-VI are the prequel and original trilogies, not the "original saga".
        assertFalse(instructions.contains("original"), instructions);
        assertTrue(instructions.contains("not an episode number"), instructions);
        assertFalse(instructions.contains("http"), instructions);
        // 2.13.0 (slice F1): the instructions point at the new arguments.
        assertTrue(instructions.contains("expand"), instructions);
        assertTrue(instructions.contains("fields"), instructions);
    }

    // 2.13.0 (slice F1): Claude Code saves a tool result over about 50 000
    // characters to a file and hands the model only the path; sw_get with
    // expand ["*"] on a film passes that. The three tools that can answer that
    // much declare the limit Claude Code reads; sw_random answers one record.
    @Test
    public void largeResultToolsDeclareMaxResultSizeChars() {
        client().when()
                .toolsList(page -> {
                    for (String name : java.util.List.of("sw_list", "sw_get", "sw_search")) {
                        var meta = page.findByName(name).meta();
                        assertNotNull(meta, name + " has no _meta");
                        assertEquals(100000, meta.getInteger("anthropic/maxResultSizeChars"), name + ": " + meta);
                    }
                    var random = page.findByName("sw_random").meta();
                    assertTrue(random == null || !random.containsKey("anthropic/maxResultSizeChars"), "" + random);
                })
                .thenAssertResults();
    }

    // 2.13.0 (slice F1): each description names its new optional argument.
    @Test
    public void descriptionsNameTheNewArguments() {
        client().when()
                .toolsList(page -> {
                    String get = page.findByName("sw_get").description();
                    assertTrue(get.contains("expand"), get);
                    assertTrue(get.contains("50 KB"), get);
                    assertTrue(page.findByName("sw_list").description().contains("fields"));
                    assertTrue(page.findByName("sw_search").description().contains("pass fields to shorten each entity"));
                })
                .thenAssertResults();
    }

    @Test
    public void toolDescriptionsNameASiblingTool() {
        java.util.List<String> names = java.util.List.of("sw_list", "sw_get", "sw_random", "sw_search");
        client().when()
                .toolsList(page -> {
                    for (String name : names) {
                        String description = page.findByName(name).description();
                        assertTrue(names.stream().filter(other -> !other.equals(name)).anyMatch(description::contains),
                                name + " must name a sibling tool: " + description);
                    }
                    assertTrue(page.findByName("sw_search").description().contains("[]"));
                    assertTrue(page.findByName("sw_get").description().contains("tool error"));
                    assertTrue(page.findByName("sw_list").description().contains("sw_search"));
                })
                .thenAssertResults();
    }

    // Guard: the sw_search description promises [] for no match.
    @Test
    public void searchWithoutMatchReturnsAnEmptyArray() {
        client().when()
                .toolsCall("sw_search")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "query", "zzzz"))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertEquals("[]", r.content().get(0).asText().text());
                })
                .send()
                .thenAssertResults();
    }

    // Guard: the resource argument description promises a tool error for any other value.
    @Test
    public void invalidResourceIsRejected() {
        client().when()
                .toolsCall("sw_list")
                .withArguments(java.util.Map.of("resource", "DROIDS"))
                .withAssert(r -> {
                    assertTrue(r.isError());
                    assertTrue(r.content().get(0).asText().text().contains("Invalid argument [resource]"),
                            r.content().get(0).asText().text());
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
