package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkus.arc.Arc;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.eldermoraes.mcp.McpMalformedRequestTest.STATELESS;
import static com.eldermoraes.mcp.Within.within;
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
 *
 * The last two tests have the real extension answer late instead, through a
 * tool of the test, in a genuine session and stateless.
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

    // ------------------------------------------------- the real extension, late

    // The synthetic routes above stand in for the extension. Here the
    // extension itself answers late: a tool of this test, registered through
    // the extension's ToolManager for the length of one test and removed
    // after it (this class has a profile of its own, so no other test ever
    // sees it in tools/list), holds its worker thread until the client has
    // the deadline's answer, then returns, and the extension writes its
    // result to a response the filter already ended.

    private static final String SLOW_TOOL = "test_slow_tool";

    // Looked up, not injected (not even as Instance<>), like every manager
    // here: an injection point would make this profile's build advertise
    // tools.listChanged (McpCapabilitiesTest, SwapiResources).
    private final ToolManager tools = Arc.container().instance(ToolManager.class).get();

    @Inject
    StreamableHttpMcpMessageHandler extensionHandler;

    @Inject
    ConnectionManager sessions;

    private final CountDownLatch toolStarted = new CountDownLatch(1);
    private final CountDownLatch toolReleased = new CountDownLatch(1);
    private final CountDownLatch toolReturned = new CountDownLatch(1);

    private void registerSlowTool() {
        tools.newTool(SLOW_TOOL)
                .setDescription("Test only: returns when the test releases it.")
                .setNotifyListChanged(false)
                .setHandler(args -> {
                    toolStarted.countDown();
                    try {
                        if (!toolReleased.await(10, TimeUnit.SECONDS)) {
                            return ToolResponse.error("never released");
                        }
                        return ToolResponse.success("late");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ToolResponse.error("interrupted");
                    } finally {
                        toolReturned.countDown();
                    }
                })
                .register();
    }

    private String openSession() throws Exception {
        HttpResponse<String> init = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25","capabilities":{},
                           "clientInfo":{"name":"deadline-test","version":"1.0"}}}""").build(), 5000);
        assertNotNull(init, "no answer");
        assertEquals(200, init.statusCode(), init.body());
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        HttpResponse<String> initialized = sendWithDeadline(post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""")
                .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25").build(), 5000);
        assertNotNull(initialized, "no answer");
        assertEquals(202, initialized.statusCode(), initialized.body());
        return session;
    }

    private HttpRequest.Builder sessionCall(String session, int id, String name, String arguments) {
        return post("""
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
                .formatted(id, name, arguments))
                .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
    }

    private HttpRequest.Builder statelessCall(int id, String name, String arguments) {
        return post("""
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"%s",
                  "io.modelcontextprotocol/clientCapabilities":{}},"name":"%s","arguments":%s}}"""
                .formatted(id, STATELESS, name, arguments))
                .header("MCP-Protocol-Version", STATELESS)
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", name);
    }

    @Test
    void aToolOfTheExtensionThatAnswersLateGetsTheDeadlineInASession() throws Exception {
        String session = openSession();
        try {
            lateTool("session", id -> sessionCall(session, id, SLOW_TOOL, "{}"),
                    id -> sessionCall(session, id, "sw_random", "{\"resource\":\"FILMS\"}"),
                    () -> post("""
                            {"jsonrpc":"2.0","id":62,"method":"tools/list"}""")
                            .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25"));
        } finally {
            sendWithDeadline(HttpRequest.newBuilder(mcp).header("Mcp-Session-Id", session).DELETE().build(), 5000);
        }
    }

    @Test
    void aToolOfTheExtensionThatAnswersLateGetsTheDeadlineStateless() throws Exception {
        lateTool("stateless", id -> statelessCall(id, SLOW_TOOL, "{}"),
                id -> statelessCall(id, "sw_random", "{\"resource\":\"FILMS\"}"),
                () -> toolsList(62));
    }

    private void lateTool(String era, java.util.function.IntFunction<HttpRequest.Builder> slowCall,
                          java.util.function.IntFunction<HttpRequest.Builder> normalCall,
                          java.util.function.Supplier<HttpRequest.Builder> list) throws Exception {
        java.util.Set<?> ongoing = McpMalformedRequestTest.ongoingRequests(extensionHandler);
        int ongoingBefore = ongoing.size();
        int sessionsBefore = McpMalformedRequestTest.connections(sessions);
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(capture);
        registerSlowTool();
        try {
            long start = System.nanoTime();
            HttpResponse<String> r = sendWithDeadline(slowCall.apply(61).build(), DEADLINE_MS + 3000);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(toolStarted.getCount() == 0, era + ": the slow tool never ran");
            // The tool is still held: the answer cannot be the tool's.
            assertEquals(1, toolReturned.getCount(), era + ": the tool returned before the deadline answered");
            assertInternalError(r, 61);
            assertTrue(r.body().contains("deadline"), r.body());
            assertTrue(ms >= DEADLINE_MS - 50 && ms < DEADLINE_MS + 2000, era + ": ended after " + ms + " ms");

            // The tool returns; the extension writes to the ended response.
            toolReleased.countDown();
            assertTrue(toolReturned.await(5, TimeUnit.SECONDS), era + ": the tool did not return");
            assertTrue(within(2000, () -> ongoing.size() == ongoingBefore
                            && McpMalformedRequestTest.connections(sessions) == sessionsBefore),
                    era + ": state left behind, ongoing " + ongoingBefore + " -> " + ongoing.size()
                            + ", sessions " + sessionsBefore + " -> " + McpMalformedRequestTest.connections(sessions));

            // The server goes on serving, on the same kind of request.
            HttpResponse<String> listed = sendWithDeadline(list.get().build(), 5000);
            assertNotNull(listed, "no answer");
            assertEquals(200, listed.statusCode(), listed.body());
            assertTrue(listed.body().contains("sw_get"), listed.body());
            HttpResponse<String> called = sendWithDeadline(normalCall.apply(63).build(), 5000);
            assertNotNull(called, "no answer");
            assertEquals(200, called.statusCode(), called.body());
            assertTrue(called.body().contains("\"result\""), called.body());
            assertFalse(called.body().contains("\"isError\":true"), called.body());

            List<String> errors = records.stream()
                    .filter(rec -> rec.getLevel().intValue() >= Level.SEVERE.intValue() || rec.getThrown() != null)
                    .map(rec -> rec.getLevel() + " " + rec.getLoggerName() + ": "
                            + McpMalformedRequestTest.formatted(rec)
                            + (rec.getThrown() == null ? "" : " " + rec.getThrown()))
                    .toList();
            assertTrue(errors.isEmpty(), era + ": the late write logged " + errors);
        } finally {
            toolReleased.countDown();
            Logger.getLogger("").removeHandler(capture);
            tools.removeTool(SLOW_TOOL);
        }
    }

    private long mcpCount() {
        Number n = given().get(stats).then().statusCode(200).extract().path("routes.'/mcp'");
        return n == null ? 0 : n.longValue();
    }
}
