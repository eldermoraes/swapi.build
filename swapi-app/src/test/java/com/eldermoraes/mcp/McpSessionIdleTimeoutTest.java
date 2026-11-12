package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkiverse.mcp.server.runtime.McpConnectionBase;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #45: a session lives in the heap until the extension's idle timeout,
 * and the sessions carry nothing this server uses. application.properties
 * sets the timeout to minutes (McpListenLimitTest.idleSessionsAreKeptForMinutes
 * guards the value); this class shows what the key does, with a profile that
 * shortens it to a second, because a test cannot wait minutes.
 *
 * "Still in the heap" is read from the extension's ConnectionManager, the map
 * the sessions live in. Over HTTP a dropped session cannot be told from a live
 * one: auto-init serves both, which is the point of the second test.
 */
@QuarkusTest
@TestProfile(McpSessionIdleTimeoutTest.ShortIdleTimeout.class)
class McpSessionIdleTimeoutTest {

    public static class ShortIdleTimeout implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.mcp.server.connection-idle-timeout", "1s");
        }
    }

    private static final String SESSION_ERA = "2025-11-25";
    // The sweep runs every half timeout: a session is gone at most 1.5 s after
    // its last use. The rest is margin for a slow machine.
    private static final long DROPPED_WITHIN_MS = 8000;

    @TestHTTPResource("/mcp")
    URI mcp;

    @Inject
    ConnectionManager sessions;

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private String openSession() throws Exception {
        HttpResponse<String> init = send(post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"%s","capabilities":{},
                           "clientInfo":{"name":"idle-timeout-test","version":"1.0"}}}
                """.formatted(SESSION_ERA)).build());
        assertEquals(200, init.statusCode(), init.body());
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session, "initialize must hand out a session");
        return session;
    }

    private List<String> heldSessions() {
        List<String> ids = new ArrayList<>();
        for (McpConnectionBase connection : sessions) {
            ids.add(connection.id());
        }
        return ids;
    }

    private static boolean within(long millis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    @Test
    void anIdleSessionIsDropped() throws Exception {
        String session = openSession();
        assertTrue(sessions.has(session), "the session exists right after the initialize");
        assertTrue(within(DROPPED_WITHIN_MS, () -> !sessions.has(session)),
                "the session is still in the heap " + DROPPED_WITHIN_MS + " ms after its last use");
    }

    // What a client sees after its session was dropped is what it sees after a
    // restart: auto-init answers, and that request leaves no session either.
    @Test
    void aDroppedSessionIdKeepsWorking() throws Exception {
        String session = openSession();
        assertTrue(within(DROPPED_WITHIN_MS, () -> !sessions.has(session)), "the session was not dropped");

        HttpResponse<String> call = send(post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                 "params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}}}
                """).header("Mcp-Session-Id", session).header("MCP-Protocol-Version", SESSION_ERA).build());
        assertEquals(200, call.statusCode(), call.body());
        assertTrue(call.body().contains("Luke Skywalker"), call.body());

        HttpResponse<String> list = send(post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/list"}
                """).header("Mcp-Session-Id", session).header("MCP-Protocol-Version", SESSION_ERA).build());
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("sw_search"), list.body());
    }

    // Extension 2.0.3 creates the session before it looks at the request, and
    // these requests end without removing it (StreamableHttpMcpMessageHandler.
    // handleStateful): none is initialized, so the removal of auto-init
    // sessions at the end of the request skips them. The idle timeout is what
    // takes them out. The test asks only for that, so it keeps passing the day
    // the extension stops leaving them.
    @Test
    void sessionsLeftByRefusedRequestsAreDropped() throws Exception {
        // 1. An unsupported MCP-Protocol-Version without a session id: 400.
        HttpResponse<String> badVersion = send(post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/list"}
                """).header("MCP-Protocol-Version", "1999-01-01").build());
        assertEquals(400, badVersion.statusCode(), badVersion.body());

        // 2. A response-shaped body: accepted (202) and discarded.
        HttpResponse<String> response = send(post("""
                {"jsonrpc":"2.0","id":5,"result":{}}
                """).build());
        assertEquals(202, response.statusCode(), response.body());

        // 3. An unknown session id with a method the protocol does not have.
        HttpResponse<String> unknownMethod = send(post("""
                {"jsonrpc":"2.0","id":6,"method":"no/such-method"}
                """).header("Mcp-Session-Id", "no-such-session").build());
        assertEquals(200, unknownMethod.statusCode(), unknownMethod.body());
        assertTrue(unknownMethod.body().contains("-32601"), unknownMethod.body());

        assertTrue(within(DROPPED_WITHIN_MS, () -> heldSessions().isEmpty()),
                "sessions still in the heap: " + heldSessions());
    }
}
