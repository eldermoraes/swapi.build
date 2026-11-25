package com.eldermoraes.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static com.eldermoraes.mcp.McpMalformedRequestTest.sendWithDeadline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 2.17.2 (issue #122): the server advertises no notification it never sends.
 * The data never changes, so no list ever changes: resources carries
 * subscribe only (the extension advertises it whenever a resource exists,
 * with no switch), and tools and prompts carry nothing.
 *
 * The extension (2.1.0, McpServerProcessor.analyzeManagerUsage) adds
 * listChanged at build time when any bean has an injection point of a
 * ResourceManager, ResourceTemplateManager, ToolManager or PromptManager,
 * Instance<> and Provider<> of them included. So no class of this
 * application, and no test of the default profile, injects one: they look
 * the manager up through Arc.container(). This test runs in the default
 * profile (no @TestProfile), whose build holds every class of src/main and
 * every test of that profile, so it fails the day someone injects a manager
 * again, or the day an upgrade of the extension (or of ArC) starts to see a
 * programmatic lookup. The whole capabilities object is compared, as JSON
 * (its keys come from a HashMap), in every revision this server supports.
 */
@QuarkusTest
class McpCapabilitiesTest {

    static final JsonObject EXPECTED = new JsonObject("""
            {"resources":{"subscribe":true},"tools":{},"prompts":{},"logging":{},
             "extensions":{"io.modelcontextprotocol/skills":{}}}""");

    @TestHTTPResource("/mcp")
    URI mcp;

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    @Test
    void initializeAdvertisesNoListChangedInAnySessionRevision() throws Exception {
        List<String> wrong = new java.util.ArrayList<>();
        for (String version : List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")) {
            HttpResponse<String> r = sendWithDeadline(post("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize",
                     "params":{"protocolVersion":"%s","capabilities":{},
                               "clientInfo":{"name":"capabilities-test","version":"1.0"}}}"""
                    .formatted(version)).build(), 5000);
            assertNotNull(r, version + ": no answer");
            assertEquals(200, r.statusCode(), version + ": " + r.body());
            JsonObject result = McpRequestMutationTest.message(r).getJsonObject("result");
            assertEquals(version, result.getString("protocolVersion"), version + ": " + r.body());
            if (!EXPECTED.equals(result.getJsonObject("capabilities"))) {
                wrong.add(version + ": " + result.getJsonObject("capabilities"));
            }
            r.headers().firstValue("Mcp-Session-Id").ifPresent(this::close);
        }
        assertEquals(List.of(), wrong, "expected " + EXPECTED + " in every revision");
    }

    @Test
    void discoverAdvertisesNoListChanged() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                  "io.modelcontextprotocol/clientInfo":{"name":"capabilities-test","version":"1.0"},
                  "io.modelcontextprotocol/clientCapabilities":{}}}}""")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "server/discover").build(), 5000);
        assertNotNull(r, "no answer");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject result = McpRequestMutationTest.message(r).getJsonObject("result");
        assertEquals(EXPECTED, result.getJsonObject("capabilities"));
    }

    private void close(String session) {
        try {
            sendWithDeadline(HttpRequest.newBuilder(mcp).header("Mcp-Session-Id", session).DELETE().build(), 5000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
