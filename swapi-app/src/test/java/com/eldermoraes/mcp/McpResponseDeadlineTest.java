package com.eldermoraes.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.eldermoraes.mcp.McpMalformedRequestTest.STATELESS;
import static com.eldermoraes.mcp.McpMalformedRequestTest.sendWithDeadline;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The net under McpTransportFilter's shape checks (2.17.1): a shape nobody
 * found yet must not hold a connection open, nor answer with Quarkus's error
 * page. Every POST the filter passes on to the extension gets a deadline
 * (swapi.mcp.response-deadline, ten seconds in application.properties, half a
 * second here so the tests stay fast), and an exception thrown on the way is
 * answered with a JSON-RPC error.
 *
 * The extension is replaced for a request by a route of the test, between
 * the filter and the extension's route, that simulates what the extension
 * did with the shapes it could not handle: answer nothing, throw, or answer
 * after the deadline. A header picks the behaviour; without it the route
 * passes the request on. auto-init is off in this profile, to check that the
 * extension's own 404 for an unknown session stays as it is.
 */
@QuarkusTest
@TestProfile(McpResponseDeadlineTest.ShortDeadline.class)
class McpResponseDeadlineTest {

    static final long DEADLINE_MS = 500;

    public static class ShortDeadline implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.mcp.response-deadline", DEADLINE_MS + "ms",
                    "quarkus.mcp.server.http.streamable.auto-init", "false");
        }
    }

    private static final String MODE = "X-Test-Extension";

    @TestHTTPResource("/mcp")
    URI mcp;

    @TestHTTPResource("/api/stats")
    URI stats;

    @Inject
    Router router;

    @Inject
    Vertx vertx;

    private Route route;

    @BeforeEach
    void addRoute() {
        // After the filters (a RouteFilter of priority p has the order -p:
        // answerWithoutState is -249) and before the extension's route.
        route = router.post("/mcp").order(-248).handler(rc -> {
            String mode = rc.request().getHeader(MODE);
            if (mode == null) {
                rc.next();
                return;
            }
            switch (mode) {
                case "silence" -> {
                    // never answers, as the extension did when a method failed in its callback
                }
                case "throw" -> throw new IllegalStateException("simulated failure in the extension");
                case "late" -> vertx.setTimer(DEADLINE_MS * 3, t -> {
                    try {
                        rc.response().putHeader("Content-Type", "application/json")
                                .end("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
                    } catch (IllegalStateException alreadyAnswered) {
                        // what the extension would meet: the deadline answered first
                    }
                });
                case "head" -> {
                    // starts an event stream and never ends it
                    rc.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
                    rc.response().write("event: message\ndata: {}\n\n");
                }
                default -> rc.next();
            }
        });
    }

    @AfterEach
    void removeRoute() {
        route.remove();
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder toolsList(Object id) {
        return post("""
                {"jsonrpc":"2.0","id":%s,"method":"tools/list","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"%s",
                  "io.modelcontextprotocol/clientCapabilities":{}}}}"""
                .formatted(id instanceof String s ? "\"" + s + "\"" : id, STATELESS))
                .header("MCP-Protocol-Version", STATELESS)
                .header("Mcp-Method", "tools/list");
    }

    private static void assertInternalError(HttpResponse<String> r, Object id) {
        assertNotNull(r, "no answer");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                r.headers().map().toString());
        JsonObject body = new JsonObject(r.body());
        assertEquals("2.0", body.getString("jsonrpc"), r.body());
        assertEquals(-32603, body.getJsonObject("error").getInteger("code"), r.body());
        assertEquals(id, body.getValue("id"), r.body());
        assertFalse(body.containsKey("details") || body.containsKey("stack"), "Quarkus's error page: " + r.body());
    }

    @Test
    void aRequestTheExtensionNeverAnswersEndsAtTheDeadline() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> r = sendWithDeadline(toolsList(41).header(MODE, "silence").build(), 5000);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertInternalError(r, 41);
        assertTrue(ms >= DEADLINE_MS - 50 && ms < DEADLINE_MS + 2000, "ended after " + ms + " ms");
        HttpResponse<String> text = sendWithDeadline(toolsList("forty-two").header(MODE, "silence").build(), 5000);
        assertInternalError(text, "forty-two");
    }

    @Test
    void anExceptionInTheExtensionIsAJsonRpcError() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> r = sendWithDeadline(toolsList(43).header(MODE, "throw").build(), 5000);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertInternalError(r, 43);
        assertTrue(ms < DEADLINE_MS, "answered at once, not at the deadline: " + ms + " ms");
    }

    // The extension writing after the deadline answered meets a response
    // already sent; the server goes on serving.
    @Test
    void anAnswerAfterTheDeadlineDoesNotBreakTheServer() throws Exception {
        HttpResponse<String> r = sendWithDeadline(toolsList(44).header(MODE, "late").build(), 5000);
        assertInternalError(r, 44);
        Thread.sleep(DEADLINE_MS * 3);
        HttpResponse<String> next = sendWithDeadline(toolsList(45).build(), 5000);
        assertNotNull(next, "no answer");
        assertEquals(200, next.statusCode(), next.body());
        assertTrue(next.body().contains("sw_get"), next.body());
    }

    // A response whose head is out cannot become an error any more: the
    // deadline closes it, so the client sees the connection end.
    @Test
    void aStartedResponseIsClosedAtTheDeadline() throws Exception {
        // Not try-with-resources: close() waits for the exchange, which never
        // ends when the deadline does not close it.
        HttpClient client = HttpClient.newHttpClient();
        var future = client.sendAsync(toolsList(46).header(MODE, "head").build(),
                HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> r = future.get(DEADLINE_MS + 3000, TimeUnit.MILLISECONDS);
            // The head came; the body ends where the deadline cut it.
            assertEquals(200, r.statusCode());
        } catch (ExecutionException closed) {
            // the connection was closed mid-body: what a client sees
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the started response was not closed at the deadline", e);
        } finally {
            future.cancel(true);
            client.shutdownNow();
        }
        HttpResponse<String> next = sendWithDeadline(toolsList(47).build(), 5000);
        assertNotNull(next, "no answer");
        assertEquals(200, next.statusCode(), next.body());
    }

    @Test
    void aRequestEndedByTheDeadlineIsCountedOnceAsMcp() throws Exception {
        long before = mcpCount();
        assertInternalError(sendWithDeadline(toolsList(48).header(MODE, "silence").build(), 5000), 48);
        assertEquals(before + 1, mcpCount());
        assertInternalError(sendWithDeadline(toolsList(49).header(MODE, "throw").build(), 5000), 49);
        assertEquals(before + 2, mcpCount());
    }

    // What the failure handler must leave alone: the extension's own refusals
    // that are not an exception.
    @Test
    void theExtensionsOwnHttpErrorsStayAsTheyAre() throws Exception {
        // Without the Accept of the transport: a bare 400 (ctx.fail(400)).
        HttpResponse<String> accept = sendWithDeadline(HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).build(), 5000);
        assertNotNull(accept, "no answer");
        assertEquals(400, accept.statusCode(), accept.body());
        assertFalse(accept.body().contains("jsonrpc"), accept.body());

        // An unknown session with auto-init off: 404 (ctx.fail(404)).
        HttpResponse<String> unknown = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                .header("Mcp-Session-Id", "not-a-session-of-this-process")
                .header("MCP-Protocol-Version", "2025-11-25").build(), 5000);
        assertNotNull(unknown, "no answer");
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertFalse(unknown.body().contains("jsonrpc"), unknown.body());

        // A body over quarkus.http.limits.max-body-size: 413. rest-assured,
        // as McpListenLimitTest.bodyLimitFitsMcp does (the server answers
        // from the Content-Length and closes during the upload).
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"pad\":\""
                        + "y".repeat(256 * 1024) + "\"}}")
        .when()
                .post(mcp)
        .then()
                .statusCode(413);
    }

    private long mcpCount() {
        Number n = given().get(stats).then().statusCode(200).extract().path("routes.'/mcp'");
        return n == null ? 0 : n.longValue();
    }
}
