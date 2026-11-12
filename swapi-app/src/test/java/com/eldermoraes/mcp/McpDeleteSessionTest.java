package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkiverse.mcp.server.runtime.McpConnectionBase;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.eldermoraes.mcp.Within.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #46: DELETE /mcp answers 204 whatever it carries, and frees the
 * session it names when this process holds it.
 *
 * "Freed" is read from the extension's ConnectionManager, as in
 * McpSessionIdleTimeoutTest: over HTTP a dropped id cannot be told from a live
 * one, auto-init serves both. This class runs with the idle timeout of
 * application.properties (minutes), so a session that is gone right after the
 * DELETE was not taken by the sweep.
 *
 * McpTransportFilter frees the session by calling ConnectionManager.remove,
 * an internal of the extension. aKnownSessionIsFreed is the guard for that:
 * it fails if a later version stops keeping the session under the id it hands
 * out in Mcp-Session-Id, or stops removing it on that call.
 */
@QuarkusTest
class McpDeleteSessionTest {

    private static final String SESSION_ERA = "2025-11-25";
    // The idle timeout here is minutes, so only the end-of-request removal can
    // take a throwaway session out within this; the rest is slow-runner margin.
    private static final long THROWAWAY_GONE_WITHIN_MS = 5000;

    @TestHTTPResource("/mcp")
    URI mcp;

    @TestHTTPResource("/mcp/")
    URI mcpWithTrailingSlash;

    @Inject
    ConnectionManager sessions;

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpRequest.Builder delete(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE();
    }

    private HttpResponse<String> deleteSession(URI uri, String session) throws Exception {
        return send(delete(uri).header("Mcp-Session-Id", session).build());
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
                           "clientInfo":{"name":"delete-session-test","version":"1.0"}}}
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

    @Test
    void aKnownSessionIsFreed() throws Exception {
        String session = openSession();
        assertTrue(sessions.has(session), "the session exists right after the initialize");

        HttpResponse<String> teardown = deleteSession(mcp, session);

        assertEquals(204, teardown.statusCode(), teardown.body());
        assertFalse(sessions.has(session), "the session is still in the heap after its DELETE");
    }

    @Test
    void aKnownSessionIsFreedThroughTheTrailingSlashToo() throws Exception {
        String session = openSession();

        HttpResponse<String> teardown = deleteSession(mcpWithTrailingSlash, session);

        assertEquals(204, teardown.statusCode(), teardown.body());
        assertFalse(sessions.has(session), "the session is still in the heap after its DELETE");
    }

    @Test
    void aSecondDeleteOfTheSameSessionIsStillNoContent() throws Exception {
        String session = openSession();
        assertEquals(204, deleteSession(mcp, session).statusCode());

        assertEquals(204, deleteSession(mcp, session).statusCode());
    }

    @Test
    void onlyTheNamedSessionIsFreed() throws Exception {
        String leaving = openSession();
        String staying = openSession();

        assertEquals(204, deleteSession(mcp, leaving).statusCode());

        assertFalse(sessions.has(leaving), "the deleted session is still in the heap");
        assertTrue(sessions.has(staying), "the DELETE of one session took another one with it");

        // Unknown, malformed and absent ids take nothing either.
        assertEquals(204, deleteSession(mcp, "no-such-session").statusCode());
        assertEquals(204, deleteSession(mcp, "../%00 not;an=id,\"at all\"")
                .statusCode());
        assertEquals(204, deleteSession(mcp, "x".repeat(4000)).statusCode());
        assertEquals(204, send(delete(mcp).build()).statusCode());
        assertTrue(sessions.has(staying), "a DELETE that named no session of this process freed one");

        assertEquals(204, deleteSession(mcp, staying).statusCode());
    }

    // A stateless MCP-Protocol-Version makes the extension's own DELETE answer
    // 405; here it is a teardown like any other.
    @Test
    void aStatelessProtocolVersionHeaderChangesNothing() throws Exception {
        String session = openSession();

        HttpResponse<String> teardown = send(delete(mcp)
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", "2026-07-28")
                .build());

        assertEquals(204, teardown.statusCode(), teardown.body());
        assertFalse(sessions.has(session), "the session is still in the heap after its DELETE");
    }

    // A client that deleted its session and uses the id again is served like
    // one whose session was dropped (auto-init), and that request does not
    // bring the session back.
    //
    // Extension 2.0.3 writes the answer (response.end in HttpMcpRequest.send)
    // before it removes the throwaway session (handle(...).onComplete in
    // StreamableHttpMcpMessageHandler.handleStateful), so right after the
    // response that session can still be in the heap. The test waits a few
    // seconds for it to go: a session really left behind stays past that and
    // still fails it. "Left" means held now and not held before, so a session
    // of another test going away meanwhile does not count.
    @Test
    void aDeletedSessionIdKeepsWorking() throws Exception {
        String session = openSession();
        assertEquals(204, deleteSession(mcp, session).statusCode());
        List<String> heldBefore = heldSessions();

        HttpResponse<String> call = send(post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                 "params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}}}
                """).header("Mcp-Session-Id", session).header("MCP-Protocol-Version", SESSION_ERA).build());

        assertEquals(200, call.statusCode(), call.body());
        assertTrue(call.body().contains("Luke Skywalker"), call.body());
        assertFalse(sessions.has(session), "the deleted session came back");
        within(THROWAWAY_GONE_WITHIN_MS, () -> heldBefore.containsAll(heldSessions()));
        List<String> left = new ArrayList<>(heldSessions());
        left.removeAll(heldBefore);
        assertEquals(List.of(), left, "the request after the DELETE left a session (before: "
                + heldBefore + ", " + THROWAWAY_GONE_WITHIN_MS + " ms after the response: " + heldSessions() + ")");
    }
}
