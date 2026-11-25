package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static com.eldermoraes.mcp.McpMalformedRequestTest.STATELESS;
import static com.eldermoraes.mcp.McpMalformedRequestTest.sendWithDeadline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why McpTransportFilter refuses the shapes of McpRequestShape, checked
 * against the extension itself (quarkus-mcp-server 2.1.0). Each test hands a
 * shape to the extension's POST handler behind a route of its own, as
 * McpListenLimitTest.theExtensionItselfStillRefusesABatch does, so the filter
 * is not in the way, and pins what the extension does with it today: an HTTP
 * 500 from Quarkus's error handler, no answer at all, -32603, or a 202 that
 * leaves a session behind.
 *
 * When one of these fails, the extension started to handle that shape itself.
 * Read its source again: the filter may leave the shape to it (and the
 * matrix of McpMalformedRequestTest then says what it answers), and the
 * enumeration of McpRequestShape must be done again for that version anyway.
 * If the control fails, it is this harness that broke, not the extension.
 *
 * The shapes left without an answer stay without one here too: each leaves
 * its entry among the extension's requests in progress in this test JVM,
 * which the other tests do not mind (they compare before and after).
 */
@QuarkusTest
class McpExtensionShapeGuardTest {

    private static final String PATH = "/test-only/mcp-extension-shapes";
    // Long enough for an answer the extension gives in milliseconds, short
    // enough for a test that waits for an answer that never comes.
    private static final long SILENCE_MS = 1500;

    @TestHTTPResource("/mcp")
    URI mcp;

    @Inject
    Router router;

    @Inject
    StreamableHttpMcpMessageHandler extensionHandler;

    @Inject
    ConnectionManager sessions;

    private Route route;

    @BeforeEach
    void addRoute() {
        route = router.post(PATH)
                .handler(BodyHandler.create())
                .handler(rc -> {
                    rc.put("mcp.http.server-name", McpServer.DEFAULT); // HttpMcpServerRecorder.CONTEXT_KEY
                    extensionHandler.handle(rc);
                });
    }

    @AfterEach
    void removeRoute() {
        route.remove();
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp.resolve(PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder stateless(String method, String body) {
        return post(body).header("MCP-Protocol-Version", STATELESS).header("Mcp-Method", method);
    }

    private static String statelessMeta() {
        return """
                "_meta":{"io.modelcontextprotocol/protocolVersion":"%s",
                  "io.modelcontextprotocol/clientCapabilities":{}}""".formatted(STATELESS);
    }

    @Test
    void controlTheRouteReachesTheExtension() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""").build(), 5000);
        assertNotNull(r, "no answer");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("sw_get"), "the test route no longer reaches the extension: " + r.body());
    }

    // The casts in the route handler: StreamableHttpMcpMessageHandler.handle
    // (the body null), McpMessageHandler.findMeta (params, _meta),
    // autoInitialRequest and Messages.decodeImplementation (clientInfo),
    // decodeInitializeRequest, ServerRequests.coerceResponseId.
    @Test
    void theExtensionAnswersHttp500ToTheseShapes() throws Exception {
        List<HttpRequest> shapes = List.of(
                post("null").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/list","params":[]}""").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"_meta":"x"}}""").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/list",
                         "params":{"_meta":{"io.modelcontextprotocol/clientInfo":{}}}}""").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"_meta":{
                          "io.modelcontextprotocol/clientInfo":{"name":"n","version":"1",
                            "icons":[{"src":"a","theme":"blue"}]}}}}""").build(),
                stateless("tools/list", """
                        {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"_meta":{
                          "io.modelcontextprotocol/protocolVersion":"%s",
                          "io.modelcontextprotocol/clientCapabilities":"x"}}}""".formatted(STATELESS)).build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"initialize",
                         "params":{"protocolVersion":"2025-11-25","capabilities":{}}}""").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                          "capabilities":[],"clientInfo":{"name":"n","version":"1"}}}""").build(),
                post("""
                        {"jsonrpc":"2.0","id":"abc","result":{}}""").build());
        for (HttpRequest shape : shapes) {
            HttpResponse<String> r = sendWithDeadline(shape, 5000);
            assertNotNull(r, "no answer");
            String what = "the extension answered " + r.statusCode() + " " + r.body();
            assertEquals(500, r.statusCode(), what);
            assertFalse(r.body().contains("\"jsonrpc\""), what);
        }
    }

    // The failures inside the callback that runs a method
    // (McpMessageHandler.operation): a null name reaches FeatureKey, which
    // refuses null (tools/call, prompts/get); ref or argument that is not an
    // object, or an argument without name or value, fail in
    // McpMessageHandler.complete and CompletionMessageHandler (Map.of).
    @Test
    void theExtensionNeverAnswersTheseShapes() throws Exception {
        List<HttpRequest> shapes = List.of(
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"arguments":{}}}""").build(),
                stateless("tools/call", """
                        {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{%s,"arguments":{}}}"""
                        .formatted(statelessMeta())).header("Mcp-Name", "sw_get").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"prompts/get","params":{}}""").build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"completion/complete",
                         "params":{"ref":"x","argument":{"name":"name","value":"V"}}}""").build(),
                stateless("completion/complete", """
                        {"jsonrpc":"2.0","id":7,"method":"completion/complete",
                         "params":{%s,"ref":{"type":"ref/prompt","name":"character_profile"},"argument":"x"}}"""
                        .formatted(statelessMeta())).build(),
                post("""
                        {"jsonrpc":"2.0","id":7,"method":"completion/complete",
                         "params":{"ref":{"type":"ref/prompt","name":"character_profile"},
                                   "argument":{"name":"name"}}}""").build());
        for (HttpRequest shape : shapes) {
            HttpResponse<String> r = sendWithDeadline(shape, SILENCE_MS);
            assertNull(r, () -> "the extension now answers this shape: " + r.statusCode() + " " + r.body());
        }
    }

    // ArgumentProviders read arguments with a cast that fails inside the
    // method's own future: the client gets "Internal error", not -32602.
    @Test
    void theExtensionAnswersInternalErrorToArgumentsThatAreNotAnObject() throws Exception {
        for (String body : List.of("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"sw_get","arguments":[]}}""", """
                {"jsonrpc":"2.0","id":7,"method":"prompts/get","params":{"name":"character_profile","arguments":[]}}""")) {
            HttpResponse<String> r = sendWithDeadline(post(body).build(), 5000);
            assertNotNull(r, "no answer");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(-32603, new JsonObject(r.body()).getJsonObject("error").getInteger("code"), r.body());
        }
    }

    // A response with an id the extension can read answers 202, but the
    // session auto-init created for it is never removed (it was never
    // initialized, which is what the removal checks): it waits for the idle
    // sweep.
    @Test
    void theExtensionKeepsASessionForAResponse() throws Exception {
        java.util.Set<String> before = ids();
        HttpResponse<String> r = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":7,"result":{}}""").build(), 5000);
        assertNotNull(r, "no answer");
        assertEquals(202, r.statusCode(), r.body());
        Thread.sleep(300);
        // By id: the idle sweep may drop older sessions meanwhile, never add one.
        java.util.Set<String> added = ids();
        added.removeAll(before);
        assertEquals(1, added.size(), "the extension no longer keeps a session for a response");
        added.forEach(sessions::remove);
    }

    private java.util.Set<String> ids() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        sessions.forEach(c -> ids.add(c.id()));
        return ids;
    }
}
