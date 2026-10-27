package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
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

    @Test
    void sessionSubscribeToAnUnknownUriIsNotFound() throws Exception {
        String session = openSession();
        HttpResponse<String> r = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":6,"method":"resources/subscribe","params":{"uri":"swapi://people/1"}}
                """).build(), DEADLINE_MS);
        assertEquals(200, r.statusCode(), r.body());
        JsonObject body = new JsonObject(r.body());
        assertEquals(-32002, body.getJsonObject("error").getInteger("code"), r.body());
        assertTrue(body.getJsonObject("error").getString("message").contains("swapi://people/1"), r.body());
        assertEquals(6, body.getInteger("id"));
        HttpResponse<String> unsubscribe = sendWithDeadline(sessionPost(session, """
                {"jsonrpc":"2.0","id":7,"method":"resources/unsubscribe","params":{"uri":"swapi://people/1"}}
                """).build(), DEADLINE_MS);
        assertEquals(-32002, new JsonObject(unsubscribe.body()).getJsonObject("error").getInteger("code"),
                unsubscribe.body());
    }

    // The state itself, not a log line: ResourceManagerImpl keeps the
    // subscribers in uriToSubscribers (a map per URI, never cleaned when the
    // session dies). The extension only records a subscriber for a resource it
    // knows, and 2.12.1 registers none, so the test registers one for the
    // duration: without the filter, 100 subscribes would leave 100 ids there.
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
    // the extension answers 400 itself, as before 2.12.1, before it creates
    // any session, and opens nothing.
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
