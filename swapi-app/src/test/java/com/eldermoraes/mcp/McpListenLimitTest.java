package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.config.McpServersRuntimeConfig;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.SmallRyeConfig;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.12.1: swapi.build sends no notifications (the data never changes), but the
 * extension answers subscriptions/listen with an SSE stream that stays open
 * with no time limit, and records one subscriber per resources/subscribe in a
 * list that is never cleaned. McpTransportFilter answers both itself.
 *
 * java.net.http with a hard deadline, not rest-assured: on 2.12.0 the listen
 * never ends, and the test must fail by time instead of hanging the build.
 */
@QuarkusTest
class McpListenLimitTest {

    private static final String STATELESS = "2026-07-28";
    private static final String SESSION_ERA = "2025-11-25";
    private static final long DEADLINE_MS = 2000;

    @TestHTTPResource("/mcp")
    URI mcp;

    @TestHTTPResource("/api/stats")
    URI stats;

    @Inject
    ResourceManager resources;

    @Inject
    Router router;

    @Inject
    StreamableHttpMcpMessageHandler extensionHandler;

    private static String statelessMeta() {
        return """
                "_meta":{"io.modelcontextprotocol/protocolVersion":"%s",
                  "io.modelcontextprotocol/clientInfo":{"name":"listen-limit-test","version":"1.0"},
                  "io.modelcontextprotocol/clientCapabilities":{}}""".formatted(STATELESS);
    }

    private static String statelessListen(String id) {
        return """
                {"jsonrpc":"2.0","id":%s,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true,"resourcesListChanged":true},%s}}
                """.formatted(id, statelessMeta());
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder statelessPost(String method, String body) {
        return post(body)
                .header("MCP-Protocol-Version", STATELESS)
                .header("Mcp-Method", method);
    }

    /** Sends and waits for the WHOLE body, failing the test after the deadline. */
    private static HttpResponse<String> sendWithDeadline(HttpRequest request, long deadlineMs) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                return future.get(deadlineMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                client.shutdownNow();
                throw new AssertionError("the response did not end within " + deadlineMs + " ms", e);
            }
        }
    }

    private String openSession() throws Exception {
        HttpResponse<String> init = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"%s","capabilities":{},
                           "clientInfo":{"name":"listen-limit-test","version":"1.0"}}}
                """.formatted(SESSION_ERA)).build(), 5000);
        assertEquals(200, init.statusCode());
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session, "initialize must hand out a session");
        return session;
    }

    private HttpRequest.Builder sessionPost(String session, String body) {
        return post(body)
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", SESSION_ERA);
    }

    private static void assertListenRefused(HttpResponse<String> r, String expectedId) {
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "a JSON answer, not an event stream: " + r.headers().map());
        JsonObject body = new JsonObject(r.body());
        assertEquals(-32601, body.getJsonObject("error").getInteger("code"), r.body());
        assertTrue(body.getJsonObject("error").getString("message").contains("subscriptions/listen"), r.body());
        assertEquals(expectedId, String.valueOf(body.getValue("id")), r.body());
    }

    @Test
    void statelessListenIsAnsweredAtOnce() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> r = sendWithDeadline(
                statelessPost("subscriptions/listen", statelessListen("\"listen-7\"")).build(), DEADLINE_MS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertListenRefused(r, "listen-7");
        assertTrue(elapsedMs < DEADLINE_MS, "took " + elapsedMs + " ms");
    }

    @Test
    void sessionListenWithTheMethodOnlyInTheBodyIsAnsweredAtOnce() throws Exception {
        String session = openSession();
        HttpResponse<String> r = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":42,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """).build(), DEADLINE_MS);
        assertListenRefused(r, "42");
        assertEquals(session, r.headers().firstValue("Mcp-Session-Id").orElse(null));
    }

    // Bug found while writing the filter: a cheap "does the raw body contain
    // subscriptions/listen" check would miss a JSON-escaped method name, which
    // the extension decodes to the same method and answers with an open stream.
    @Test
    void listenWithAnEscapedMethodNameIsAnsweredAtOnce() throws Exception {
        String session = openSession();
        HttpResponse<String> r = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":43,"method":"subscriptions\\/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """).build(), DEADLINE_MS);
        assertListenRefused(r, "43");
        HttpResponse<String> unicode = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":44,"method":"subscriptions\\u002flisten",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """).build(), DEADLINE_MS);
        assertListenRefused(unicode, "44");
    }

    @Test
    void batchWithListenIsRejected() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post("""
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"},
                 {"jsonrpc":"2.0","id":2,"method":"subscriptions/listen","params":{"notifications":{}}}]
                """).build(), DEADLINE_MS);
        assertEquals(400, r.statusCode(), r.body());
        JsonObject body = new JsonObject(r.body());
        assertEquals(-32600, body.getJsonObject("error").getInteger("code"), r.body());
        assertTrue(body.getJsonObject("error").getString("message").contains("batch"), r.body());
    }

    // Issue #70: a JSON array is one answer, whatever it carries. Before, only
    // the batch with a listen was answered here (400, -32600) and every other
    // one went on to the extension (200, -32700 "Unable to parse", or a bare
    // 400 without the Accept the transport requires).
    @Test
    void everyBatchGetsTheSameAnswer() throws Exception {
        String plain = """
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"},
                 {"jsonrpc":"2.0","id":2,"method":"resources/list"}]
                """;
        String withListen = """
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"},
                 {"jsonrpc":"2.0","id":2,"method":"subscriptions/listen","params":{"notifications":{}}}]
                """;
        String session = openSession();
        Map<String, HttpRequest> batches = new java.util.LinkedHashMap<>();
        batches.put("plain", post(plain).build());
        batches.put("with a listen", post(withListen).build());
        batches.put("empty", post("[]").build());
        batches.put("of one", post("""
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"}]
                """).build());
        batches.put("of things that are not requests", post("[1,\"two\",null]").build());
        batches.put("plain, with the listen header",
                post(plain).header("Mcp-Method", "subscriptions/listen").build());
        batches.put("plain, stateless", statelessPost("tools/list", plain).build());
        batches.put("with a listen, stateless", statelessPost("subscriptions/listen", withListen).build());
        batches.put("plain, without the Accept of the transport", HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(plain)).build());

        JsonObject expected = new JsonObject(sendWithDeadline(batches.get("with a listen"), DEADLINE_MS).body());
        for (Map.Entry<String, HttpRequest> batch : batches.entrySet()) {
            HttpResponse<String> r = sendWithDeadline(batch.getValue(), DEADLINE_MS);
            String what = "batch " + batch.getKey() + ": " + r.statusCode() + " " + r.body();
            assertEquals(400, r.statusCode(), what);
            assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                    "a JSON answer, not an event stream: " + what);
            JsonObject body = new JsonObject(r.body());
            assertEquals(-32600, body.getJsonObject("error").getInteger("code"), what);
            assertTrue(body.getJsonObject("error").getString("message").contains("batch"), what);
            assertTrue(body.containsKey("id") && body.getValue("id") == null, what);
            assertFalse(body.containsKey("result"), what);
            assertEquals(expected, body, what);
        }

        // In a session the answer is the same and carries the session id back,
        // as every answer of the filter does.
        HttpResponse<String> inSession = sendWithDeadline(sessionPost(session, plain).build(), DEADLINE_MS);
        assertEquals(400, inSession.statusCode(), inSession.body());
        assertEquals(expected, new JsonObject(inSession.body()));
        assertEquals(session, inSession.headers().firstValue("Mcp-Session-Id").orElse(null));
    }

    // The stats page says so (pages/stats.ts, "a batch counts once"): /mcp
    // counts HTTP requests, and a refused batch is one.
    @Test
    void batchIsCountedOnceAsMcp() throws Exception {
        long before = mcpCount();
        HttpResponse<String> r = sendWithDeadline(post("""
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"},
                 {"jsonrpc":"2.0","id":2,"method":"tools/list"},
                 {"jsonrpc":"2.0","id":3,"method":"tools/list"}]
                """).build(), DEADLINE_MS);
        assertEquals(400, r.statusCode(), r.body());
        assertEquals(before + 1, mcpCount());
    }

    // Why the filter's batch guard exists, checked (issue #70). The filter
    // answers every JSON array, so no batch reaches the extension; this test
    // hands a batch to the extension's POST handler behind a route of its
    // own, setting the server name as the extension's route does before it
    // calls the same handler (HttpMcpServerRecorder; that wrapper is not
    // reachable from here, so a batch split there would not be seen). In
    // 2.0.3 the handler casts the decoded body to JsonObject inside its try,
    // so any array is answered as a parse error (200, -32700) before a
    // session or a stream exists: today the guard is a second lock. If the
    // batch part fails, an upgraded extension started to accept batches: a
    // subscriptions/listen inside one would open a stream with no limit, the
    // guard became the only thing in the way, and it must stay (decide then
    // whether batches should be served at all). If the control fails, it is
    // this harness that broke (a renamed context key, a changed handler), not
    // the extension's answer to batches.
    @Test
    void theExtensionItselfStillRefusesABatch() throws Exception {
        String path = "/test-only/mcp-extension-batch";
        Route route = router.post(path)
                .handler(BodyHandler.create())
                .handler(rc -> {
                    rc.put("mcp.http.server-name", McpServer.DEFAULT); // HttpMcpServerRecorder.CONTEXT_KEY
                    extensionHandler.handle(rc);
                });
        try {
            // Control: the same route serves a single request, so the body
            // does reach the handler and -32700 below is about the array.
            HttpResponse<String> control = sendWithDeadline(HttpRequest.newBuilder(mcp.resolve(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", STATELESS)
                    .header("Mcp-Method", "tools/list")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{%s}}
                            """.formatted(statelessMeta()))).build(), 5000);
            assertEquals(200, control.statusCode(), control.body());
            assertTrue(control.body().contains("sw_get"),
                    "the test route no longer reaches the extension's handler: " + control.body());

            for (String batch : java.util.List.of("""
                    [{"jsonrpc":"2.0","id":1,"method":"tools/list"},
                     {"jsonrpc":"2.0","id":2,"method":"resources/list"}]
                    """, """
                    [{"jsonrpc":"2.0","id":1,"method":"tools/list"},
                     {"jsonrpc":"2.0","id":2,"method":"subscriptions/listen","params":{"notifications":{}}}]
                    """, "[" + statelessListen("3") + "]")) {
                HttpResponse<String> r = sendWithDeadline(HttpRequest.newBuilder(mcp.resolve(path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(batch)).build(), DEADLINE_MS);
                String what = "the extension answered a batch with " + r.statusCode() + " " + r.body();
                assertEquals(200, r.statusCode(), what);
                assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                        "a JSON answer, not an event stream: " + r.headers().map());
                assertTrue(r.body().strip().startsWith("{"), what);
                JsonObject body = new JsonObject(r.body());
                assertEquals(-32700, body.getJsonObject("error").getInteger("code"), what);
                assertFalse(body.containsKey("result"), what);
                assertTrue(r.headers().firstValue("Mcp-Session-Id").isEmpty(), "no session for a batch: " + what);
            }
        } finally {
            route.remove();
        }
    }

    // Guard: on the stateless era the extension already refuses subscribe
    // without keeping anything (conferred on production, 27/10).
    @Test
    void statelessSubscribeIsMethodNotFound() throws Exception {
        HttpResponse<String> r = sendWithDeadline(statelessPost("resources/subscribe", """
                {"jsonrpc":"2.0","id":5,"method":"resources/subscribe",
                 "params":{"uri":"swapi://people/1",%s}}
                """.formatted(statelessMeta())).build(), DEADLINE_MS);
        assertEquals(200, r.statusCode(), r.body());
        JsonObject body = new JsonObject(r.body());
        assertEquals(-32601, body.getJsonObject("error").getInteger("code"), r.body());
        assertTrue(body.getJsonObject("error").getString("message").contains("stateless"), r.body());
    }

    // 2.13.0 (slice F1): swapi://people/1 became a resource, so the unknown
    // URI is a record that does not exist.
    @Test
    void sessionSubscribeToAnUnknownUriIsNotFound() throws Exception {
        String session = openSession();
        HttpResponse<String> r = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":6,"method":"resources/subscribe","params":{"uri":"swapi://people/9999"}}
                """).build(), DEADLINE_MS);
        assertEquals(200, r.statusCode(), r.body());
        JsonObject body = new JsonObject(r.body());
        assertEquals(-32002, body.getJsonObject("error").getInteger("code"), r.body());
        assertTrue(body.getJsonObject("error").getString("message").contains("swapi://people/9999"), r.body());
        assertEquals(6, body.getInteger("id"));
        HttpResponse<String> unsubscribe = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":7,"method":"resources/unsubscribe","params":{"uri":"swapi://people/9999"}}
                """).build(), DEADLINE_MS);
        assertEquals(-32002, new JsonObject(unsubscribe.body()).getJsonObject("error").getInteger("code"),
                unsubscribe.body());
    }

    // 2.13.0 (slice F1): a subscribe to a real resource is accepted ({}) and
    // still leaves nothing in the extension's subscriber map.
    @Test
    void sessionSubscribeToAKnownUriSucceedsWithoutState() throws Exception {
        Map<?, ?> subscribers = subscribers();
        String session = openSession();
        for (String uri : java.util.List.of("swapi://people/1", "swapi://films")) {
            HttpResponse<String> r = sendWithDeadline(sessionPost(session, """
                    {"jsonrpc":"2.0","id":14,"method":"resources/subscribe","params":{"uri":"%s"}}
                    """.formatted(uri)).build(), DEADLINE_MS);
            assertEquals(200, r.statusCode(), r.body());
            JsonObject body = new JsonObject(r.body());
            assertEquals(new JsonObject(), body.getJsonObject("result"), r.body());
            assertEquals(14, body.getInteger("id"));
        }
        assertTrue(subscribers.isEmpty(), "the extension recorded subscribers for " + subscribers.keySet());
    }

    // The state itself, not a log line: ResourceManagerImpl keeps the
    // subscribers in uriToSubscribers (a map per URI, never cleaned when the
    // session dies). The extension only records a subscriber for a resource it
    // knows; the test registers its own for the duration (2.12.1 had none):
    // without the filter, 100 subscribes would leave 100 ids there.
    @Test
    void sessionSubscribeKeepsNoState() throws Exception {
        String uri = "swapi://test/keeps-no-state";
        resources.newResource("keeps-no-state")
                .setUri(uri)
                .setDescription("registered by McpListenLimitTest")
                .setHandler(args -> new ResourceResponse(TextResourceContents.create(uri, "x")))
                .register();
        try {
            Map<?, ?> subscribers = subscribers();
            String session = openSession();
            for (int i = 0; i < 100; i++) {
                String method = i % 10 == 9 ? "resources/unsubscribe" : "resources/subscribe";
                String message = """
                        {"jsonrpc":"2.0","id":%d,"method":"%s","params":{"uri":"%s"}}
                        """.formatted(100 + i, method, uri);
                // Half with the session, half without: with auto-init the
                // extension gives a request without Mcp-Session-Id a session too.
                HttpRequest request = i % 2 == 0
                        ? sessionPost(session, message).build()
                        : post(message).header("MCP-Protocol-Version", SESSION_ERA).build();
                HttpResponse<String> r = sendWithDeadline(request, DEADLINE_MS);
                assertEquals(200, r.statusCode(), r.body());
                JsonObject body = new JsonObject(r.body());
                assertEquals(new JsonObject(), body.getJsonObject("result"), r.body());
                assertEquals(100 + i, body.getInteger("id"));
            }
            assertTrue(subscribers.isEmpty(), "the extension recorded subscribers for "
                    + subscribers.keySet() + ": " + subscribers.values().stream()
                            .mapToInt(ids -> ((java.util.List<?>) ids).size()).sum() + " ids");
        } finally {
            resources.removeResource(uri);
        }
    }

    private Map<?, ?> subscribers() throws Exception {
        Object manager = ClientProxy.unwrap(resources);
        Field field = manager.getClass().getDeclaredField("uriToSubscribers");
        field.setAccessible(true);
        Object value = field.get(manager);
        assertNotNull(value, "ResourceManagerImpl.uriToSubscribers");
        return (Map<?, ?>) value;
    }

    // The filter only answers a subscribe the extension would have handled.
    // With an unsupported protocol version or without the required Accept,
    // the extension answers 400 itself, as before 2.12.1, records no
    // subscriber and opens nothing. Only the Accept is refused before any
    // session exists. A bad version is refused after the session lookup: with
    // the real session id these tests send nothing new is created, but
    // without one (or with an unknown one) extension 2.0.3 creates a session
    // and leaves it to the idle timeout (issue #45,
    // McpSessionIdleTimeoutTest.sessionsLeftByRefusedRequestsAreDropped).
    @Test
    void subscribeWithoutTheRequiredAcceptIsLeftToTheExtension() throws Exception {
        String session = openSession();
        HttpResponse<String> r = sendWithDeadline(HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", SESSION_ERA)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":12,"method":"resources/subscribe","params":{"uri":"swapi://people/1"}}
                        """)).build(), DEADLINE_MS);
        assertEquals(400, r.statusCode(), r.body());
        assertFalse(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
    }

    @Test
    void subscribeWithAnUnsupportedProtocolVersionIsLeftToTheExtension() throws Exception {
        String session = openSession();
        HttpResponse<String> r = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":13,"method":"resources/subscribe","params":{"uri":"swapi://people/1"}}
                """).header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "1999-01-01").build(),
                DEADLINE_MS);
        assertEquals(400, r.statusCode(), r.body());
        assertFalse(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
    }

    // Only the Mcp-Method header says listen: no method in the body.
    @Test
    void listenByTheHeaderOnlyIsAnsweredAtOnce() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":45,"params":{"notifications":{}}}
                """).header("Mcp-Method", "subscriptions/listen").build(), DEADLINE_MS);
        assertListenRefused(r, "45");
    }

    @Test
    void listenHeaderWithABodyThatIsNotJsonIsAnsweredAtOnce() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post("not json {")
                .header("Mcp-Method", "subscriptions/listen").build(), DEADLINE_MS);
        assertListenRefused(r, "null");
    }

    @Test
    void toolCallsStillWorkAfterTheFilterReadTheBody() throws Exception {
        HttpResponse<String> stateless = sendWithDeadline(statelessPost("tools/call", """
                {"jsonrpc":"2.0","id":8,"method":"tools/call",
                 "params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1},%s}}
                """.formatted(statelessMeta())).header("Mcp-Name", "sw_get").build(), 5000);
        assertEquals(200, stateless.statusCode(), stateless.body());
        assertTrue(stateless.body().contains("Luke Skywalker"), stateless.body());

        String session = openSession();
        HttpResponse<String> inSession = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":9,"method":"tools/call",
                 "params":{"name":"sw_search","arguments":{"resource":"PLANETS","query":"Tatooine"}}}
                """).build(), 5000);
        assertEquals(200, inSession.statusCode(), inSession.body());
        assertTrue(inSession.body().contains("Tatooine"), inSession.body());

        // A body of 200 KB (under the 256K limit), read in chunks, reaches the
        // extension whole.
        String pad = "x".repeat(200 * 1024);
        HttpResponse<String> big = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call",
                 "params":{"name":"sw_get","arguments":{"resource":"FILMS","id":1},"pad":"%s"}}
                """.formatted(pad)).build(), 10000);
        assertEquals(200, big.statusCode());
        assertTrue(big.body().contains("A New Hope"), big.body().substring(0, Math.min(300, big.body().length())));
    }

    // 2.12.1: quarkus.http.limits.max-body-size=256K (the default was 10240K).
    // The filter decodes every POST to /mcp on the event loop before the
    // extension decodes it again; the largest real MCP request here is an
    // initialize of a few KB. A body exactly at the limit still reaches the
    // extension; one byte more is 413, before anything decodes it.
    @Test
    void bodyLimitFitsMcp() throws Exception {
        long limit = 256L * 1024;
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\",\"params\":{\"pad\":\"";
        String suffix = "\"}}";
        int padUnder = (int) (limit - prefix.length() - suffix.length());
        HttpResponse<String> under = sendWithDeadline(
                post(prefix + "y".repeat(padUnder) + suffix).build(), 20000);
        assertEquals(200, under.statusCode());
        assertTrue(under.body().contains("sw_get"), "a body exactly at the limit still reaches the extension");

        // rest-assured here: the server answers 413 from the Content-Length and
        // closes while java.net.http is still uploading (ClosedChannelException).
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body(prefix + "y".repeat(padUnder + 1) + suffix)
        .when()
                .post(mcp)
        .then()
                .statusCode(413);
    }

    // Issue #45: a session (a genuine initialize, or one of the refused
    // requests of McpSessionIdleTimeoutTest) stays in the heap until the
    // extension's idle timeout, and its default is 30 minutes. The sessions
    // carry nothing this server uses and auto-init serves a dropped id, so
    // minutes are enough. Read from the mapping the extension itself reads.
    @Test
    void idleTimeoutIsMinutes() {
        Duration timeout = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                .getConfigMapping(McpServersRuntimeConfig.class)
                .servers().get(McpServer.DEFAULT).connectionIdleTimeout();
        assertTrue(timeout.compareTo(Duration.ofMinutes(1)) >= 0 && timeout.compareTo(Duration.ofMinutes(5)) <= 0,
                "quarkus.mcp.server.connection-idle-timeout is " + timeout + ", expected 1 to 5 minutes");
    }

    @Test
    void listenIsCountedOnceAsMcp() throws Exception {
        long before = mcpCount();
        long start = System.nanoTime();
        HttpResponse<String> r = sendWithDeadline(
                statelessPost("subscriptions/listen", statelessListen("1")).build(), DEADLINE_MS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertListenRefused(r, "1");
        assertTrue(elapsedMs < 1000, "the listen took " + elapsedMs + " ms");
        assertEquals(before + 1, mcpCount());
    }

    private long mcpCount() {
        Number n = given().get(stats).then().statusCode(200).extract().path("routes.'/mcp'");
        return n == null ? 0 : n.longValue();
    }
}
