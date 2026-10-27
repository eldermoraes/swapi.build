package com.eldermoraes.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    @Test
    void sessionSubscribeKeepsNoState() throws Exception {
        // The extension's handler logs "Subscribe to resource" at DEBUG right
        // before it records the subscriber; with the filter in front it never runs.
        // The pair: "List resources" from the same logger proves the capture works.
        Logger handlerLogger = Logger.getLogger("io.quarkiverse.mcp.server.runtime.ResourceMessageHandler");
        Level previous = handlerLogger.getLevel();
        boolean parents = handlerLogger.getUseParentHandlers();
        List<String> seen = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (seen) {
                    String message = record.getMessage();
                    if (record.getParameters() != null && record.getParameters().length > 0) {
                        message = String.format(message, record.getParameters());
                    }
                    seen.add(message);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        handlerLogger.setLevel(Level.FINE);
        handlerLogger.setUseParentHandlers(false);
        handlerLogger.addHandler(capture);
        try {
            String session = openSession();
            for (int i = 0; i < 100; i++) {
                HttpResponse<String> r = sendWithDeadline(sessionPost(session, """
                        {"jsonrpc":"2.0","id":%d,"method":"resources/subscribe","params":{"uri":"swapi://people/%d"}}
                        """.formatted(100 + i, i)).build(), DEADLINE_MS);
                assertEquals(200, r.statusCode(), r.body());
            }
            HttpResponse<String> list = sendWithDeadline(sessionPost(session, """
                    {"jsonrpc":"2.0","id":999,"method":"resources/list"}
                    """).build(), DEADLINE_MS);
            assertTrue(list.body().contains("\"resources\":[]"), list.body());
        } finally {
            handlerLogger.removeHandler(capture);
            handlerLogger.setUseParentHandlers(parents);
            handlerLogger.setLevel(previous);
        }
        synchronized (seen) {
            assertTrue(seen.stream().anyMatch(m -> m.contains("List resources")),
                    "the log capture must see the extension's DEBUG lines: " + seen);
            assertFalse(seen.stream().anyMatch(m -> m.contains("Subscribe to resource")),
                    "the extension handled a subscribe: " + seen);
        }
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

        // A body of several hundred KB, read in chunks, reaches the extension whole.
        String pad = "x".repeat(512 * 1024);
        HttpResponse<String> big = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call",
                 "params":{"name":"sw_get","arguments":{"resource":"FILMS","id":1},"pad":"%s"}}
                """.formatted(pad)).build(), 10000);
        assertEquals(200, big.statusCode());
        assertTrue(big.body().contains("A New Hope"), big.body().substring(0, Math.min(300, big.body().length())));
    }

    // Guard: the body limit stays the Quarkus default (10240K, no
    // quarkus.http.limits.max-body-size in application.properties).
    @Test
    void bodyLimitIsTheSameAsBefore() throws Exception {
        long limit = 10240L * 1024;
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
