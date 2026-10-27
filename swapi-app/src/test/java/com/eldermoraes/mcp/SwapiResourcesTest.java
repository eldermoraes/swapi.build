package com.eldermoraes.mcp;

import com.eldermoraes.seo.KnownEntities;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkiverse.mcp.server.test.McpAssured.ResourcesPage;
import io.quarkiverse.mcp.server.test.McpAssured.ResourcesTemplatesPage;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.13.0 (slice F1): the records as MCP resources, concrete (in
 * resources/list, what the "@" picker of Claude Code shows) and as templates.
 */
@QuarkusTest
@TestProfile(SwapiResourcesTest.SameHostProfile.class)
class SwapiResourcesTest {

    /**
     * The base the test client's requests resolve to anyway, fixed so the
     * template methods can be called directly. The discovery from the request
     * host is SwapiBaseUrlDiscoveryTest.resourceUrlsUseTheRequestHost.
     */
    public static class SameHostProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.public-base-url", "http://localhost:8081/api");
        }
    }

    private static final List<String> TYPES = List.of("people", "films", "planets", "species", "starships", "vehicles");

    static McpStreamableTestClient client;

    @Inject
    KnownEntities known;

    @Inject
    SwapiResources resources;

    @TestHTTPResource("/mcp")
    URI mcp;

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
            client = null;
        }
    }

    private static ResourcesPage list() {
        AtomicReference<ResourcesPage> page = new AtomicReference<>();
        client().when().resourcesList(page::set).thenAssertResults();
        return page.get();
    }

    private static String read(String uri) {
        AtomicReference<ResourceResponse> response = new AtomicReference<>();
        client().when().resourcesRead(uri).withAssert(response::set).send().thenAssertResults();
        assertEquals(1, response.get().contents().size(), uri);
        TextResourceContents contents = response.get().contents().get(0).asText();
        assertEquals(uri, contents.uri());
        assertEquals("application/json", contents.mimeType());
        return contents.text();
    }

    private int recordCount() {
        int n = 0;
        for (String type : TYPES) {
            n += known.ids(type).size();
        }
        return n;
    }

    @Test
    void resourcesListHasEveryRecordInOnePage() {
        ResourcesPage page = list();
        assertNull(page.nextCursor(), "one page: quarkus.mcp.server.resources.page-size");
        assertEquals(260, recordCount());
        assertEquals(6 + recordCount(), page.size());
        for (String type : TYPES) {
            for (int id : known.ids(type)) {
                var info = page.findByUri("swapi://" + type + "/" + id);
                assertNotNull(info, type + "/" + id);
                assertEquals(known.name(type, Integer.toString(id)).orElseThrow() + " (" + type + "/" + id + ")",
                        info.name());
                assertEquals("application/json", info.mimeType());
            }
        }
    }

    @Test
    void resourcesListHasTheSixIndexes() {
        ResourcesPage page = list();
        for (String type : TYPES) {
            var info = page.findByUri("swapi://" + type);
            assertNotNull(info, type);
            assertEquals(type, info.name());
        }
    }

    @Test
    void templatesListHasTheSixTypes() {
        AtomicReference<ResourcesTemplatesPage> page = new AtomicReference<>();
        client().when().resourcesTemplatesList(page::set).thenAssertResults();
        List<String> templates = page.get().templates().stream().map(t -> t.uriTemplate()).sorted().toList();
        assertEquals(TYPES.stream().map(t -> "swapi://" + t + "/{id}").sorted().toList(), templates);
    }

    @Test
    void readPersonOneEqualsTheSwGetText() {
        for (String type : TYPES) {
            int id = known.ids(type).getFirst();
            AtomicReference<ToolResponse> tool = new AtomicReference<>();
            client().when().toolsCall("sw_get").withArguments(Map.of("resource", type.toUpperCase(), "id", id))
                    .withAssert(tool::set).send().thenAssertResults();
            assertEquals(tool.get().content().get(0).asText().text(), read("swapi://" + type + "/" + id), type);
        }
    }

    // The concrete resource wins on resources/read; the template, called
    // directly (this profile fixes the base, so no request is needed), gives
    // the same text and URI for the same record.
    @Test
    void templateReadEqualsTheConcreteRead() {
        assertEquals(read("swapi://people/1"), resources.peopleRecord("1").text());
        assertEquals("swapi://people/1", resources.peopleRecord("1").uri());
        assertEquals(read("swapi://films/6"), resources.filmsRecord("6").text());
        assertEquals(read("swapi://vehicles/76"), resources.vehiclesRecord("76").text());
        assertNull(resources.starshipsRecord("1"), "starships has no record 1");
    }

    @Test
    void indexHasEveryKnownId() {
        for (String type : TYPES) {
            JsonArray index = new JsonArray(read("swapi://" + type));
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < index.size(); i++) {
                JsonObject row = index.getJsonObject(i);
                assertEquals(List.of("id", "name", "uri", "url"), List.copyOf(row.fieldNames()));
                int id = row.getInteger("id");
                ids.add(id);
                assertEquals(known.name(type, Integer.toString(id)).orElseThrow(), row.getString("name"));
                assertEquals("swapi://" + type + "/" + id, row.getString("uri"));
                assertEquals("http://localhost:8081/api/" + type + "/" + id, row.getString("url"));
            }
            assertEquals(known.ids(type), ids, type);
        }
        assertTrue(read("swapi://films").contains("{\"id\":1,\"name\":\"A New Hope\",\"uri\":\"swapi://films/1\""));
    }

    private HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> statelessRead(String uri) throws Exception {
        return post("""
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"%s",
                 "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                  "io.modelcontextprotocol/clientInfo":{"name":"resources-test","version":"1.0"},
                  "io.modelcontextprotocol/clientCapabilities":{}}}}
                """.formatted(uri), Map.of("MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "resources/read", "Mcp-Name", uri));
    }

    // Pair: the same request for a record that exists has the record.
    @Test
    void unknownIdIsNotFoundForStatelessClients() throws Exception {
        HttpResponse<String> luke = statelessRead("swapi://people/1");
        assertEquals(200, luke.statusCode(), luke.body());
        assertTrue(luke.body().contains("Luke Skywalker"), luke.body());
        HttpResponse<String> none = statelessRead("swapi://people/9999");
        assertEquals(200, none.statusCode(), none.body());
        JsonObject body = new JsonObject(none.body());
        assertEquals(-32602, body.getJsonObject("error").getInteger("code"), none.body());
        assertFalse(none.body().contains("Luke Skywalker"));
    }

    @Test
    void unknownIdIsNotFoundForSessionClients() {
        client().when().resourcesRead("swapi://people/9999")
                .withErrorAssert(e -> assertEquals(-32002, e.code(), e.message()))
                .send()
                .thenAssertResults();
        assertTrue(read("swapi://people/1").contains("Luke Skywalker"));
    }

    @Test
    void nonCanonicalIdIsNotFound() {
        for (String uri : List.of("swapi://people/01", "swapi://people/1a", "swapi://people/-1",
                "swapi://people/0", "swapi://people/", "swapi://droids/1")) {
            client().when().resourcesRead(uri)
                    .withErrorAssert(e -> assertEquals(-32002, e.code(), uri + ": " + e.message()))
                    .send()
                    .thenAssertResults();
        }
        for (String id : List.of("01", "1a", "-1", "0", "", " 1", "1 ", "9999999999")) {
            assertNull(resources.peopleRecord(id), "'" + id + "'");
        }
        assertNull(resources.peopleRecord(null));
    }
}
