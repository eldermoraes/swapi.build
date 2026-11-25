package com.eldermoraes.mcp;

import com.eldermoraes.mcp.McpMalformedRequestTest.Era;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.eldermoraes.mcp.McpMalformedRequestTest.STATELESS;
import static com.eldermoraes.mcp.McpMalformedRequestTest.SESSION_ERA;
import static com.eldermoraes.mcp.McpMalformedRequestTest.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.17.2 (#126): the refusals that the MCP extension (quarkus-mcp-server
 * 2.1.0) answers cleanly but logs at every request, with the client's text
 * unescaped in some of them: a body that is not a JSON object
 * (StreamableHttpMcpMessageHandler, a WARN with a stack trace), an Accept
 * without both types of the transport (the same class, a WARN that repeats
 * the header), a cursor that does not decode (Messages.getCursor, a WARN
 * that repeats it) and a notifications/cancelled that matches no request in
 * progress (McpMessageHandler.cancelRequest, a WARN that repeats the
 * requestId). McpTransportFilter writes these answers itself, logged once per
 * kind per minute (the cancel not at all), so that no malformed request
 * makes the server log a line per request.
 *
 * Two kinds of test, both against the extension's own POST handler behind a
 * route of the test (as McpExtensionShapeGuardTest), so the filter is not in
 * the way:
 * - the guard: the extension still answers each of these as below and still
 *   logs it at every request. When it fails, the extension changed: read its
 *   source again (the paths are in McpTransportFilter) and decide whether the
 *   filter still has to write the answer.
 * - the comparison: the same request sent to that route and to /mcp gets the
 *   same status, the same JSON body (or none), the same Content-Type and the
 *   same presence of Mcp-Session-Id, in the three eras; and through /mcp no
 *   logger but the filter's logs a WARN or above, and no line carries text of
 *   the request. Pairs that the extension serves are compared too, so a check
 *   of the filter that is too wide shows here.
 */
@QuarkusTest
class McpExtensionLogGuardTest {

    private static final String PATH = "/test-only/mcp-extension-logs";
    private static final String ACCEPT = "application/json, text/event-stream";
    // Text of the request that must never reach the log through /mcp.
    static final String MARKER = "zq-marker";
    private static final String PARSE_ERROR = """
            {"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Unable to parse the JSON message"}}""";

    @TestHTTPResource("/mcp")
    URI mcp;

    @Inject
    Router router;

    @Inject
    StreamableHttpMcpMessageHandler extensionHandler;

    private Route route;
    private HttpClient client;
    private String session;

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
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

    @BeforeEach
    void setUp() throws Exception {
        // The uploads directory of Quarkus's body handler (quarkus.http.body),
        // not the Vert.x default, which is relative to the working directory.
        route = router.post(PATH)
                .handler(BodyHandler.create().setUploadsDirectory(System.getProperty("java.io.tmpdir") + "/uploads"))
                .handler(rc -> {
                    rc.put("mcp.http.server-name", McpServer.DEFAULT); // HttpMcpServerRecorder.CONTEXT_KEY
                    extensionHandler.handle(rc);
                });
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        capture.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(capture);
        session = openSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        send(HttpRequest.newBuilder(mcp).header("Mcp-Session-Id", session).DELETE().build());
        Logger.getLogger("").removeHandler(capture);
        route.remove();
        client.shutdownNow();
    }

    // ------------------------------------------------------------- requests

    /** A POST to /mcp, or to the extension's route; accepts: the Accept headers, none if empty. */
    record Req(String name, Era era, String contentType, List<String> accepts, Map<String, String> headers,
               byte[] body) {
    }

    static Map<String, String> eraHeaders(Era era, String method) {
        Map<String, String> h = new LinkedHashMap<>();
        switch (era) {
            case AUTO_INIT -> {
            }
            case SESSION -> h.put("MCP-Protocol-Version", SESSION_ERA);
            case STATELESS -> {
                h.put("MCP-Protocol-Version", STATELESS);
                if (method != null) {
                    h.put("Mcp-Method", method);
                }
            }
        }
        return h;
    }

    static Req req(String name, Era era, String method, String body) {
        return new Req(name + " [" + era + "]", era, "application/json", List.of(ACCEPT), eraHeaders(era, method),
                body.getBytes(StandardCharsets.UTF_8));
    }

    static Req with(Req r, String header, String value) {
        Map<String, String> h = new LinkedHashMap<>(r.headers());
        if (value == null) {
            h.remove(header);
        } else {
            h.put(header, value);
        }
        return new Req(r.name() + " " + header + "=" + value, r.era(), r.contentType(), r.accepts(), h, r.body());
    }

    static Req accepts(Req r, List<String> accepts) {
        return new Req(r.name() + " Accept " + accepts, r.era(), r.contentType(), accepts, r.headers(), r.body());
    }

    /** A request of method with the members given, the era's _meta in params; id null = no id. */
    static String request(Era era, Object id, String method, String members) {
        return "{\"jsonrpc\":\"2.0\"," + (id == null ? "" : "\"id\":" + id + ",") + "\"method\":\"" + method
                + "\",\"params\":" + params(era, members) + "}";
    }

    static String list(Era era, String method, String cursorJson) {
        return request(era, 7, method, "\"cursor\":" + cursorJson);
    }

    static String cancel(Era era, String members) {
        return request(era, null, "notifications/cancelled", members);
    }

    static String json(String text) {
        return io.vertx.core.json.Json.encode(text);
    }

    static String base64(int bytes, byte fill) {
        byte[] b = new byte[bytes];
        java.util.Arrays.fill(b, fill);
        return Base64.getEncoder().encodeToString(b);
    }

    // The cursors on both sides of the extension's rule (Cursor.decode):
    // not blank, basic Base64, 24 bytes, and two Instant.ofEpochSecond that
    // fit. The initial cursor (all zero) and the 24 bytes of 0xff decode; the
    // first long above Instant.MAX does not.
    static final List<String> CURSORS = List.of(
            json("x"), json(MARKER), json(""), json("   "), json("!!!!"), json("AAAA AAAA"),
            json(base64(23, (byte) 0)), json(base64(23, (byte) 0).replace("=", "")),
            json(base64(25, (byte) 0)), json(base64(24, (byte) 0) + "="),
            json(base64(24, (byte) 0)), json("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB"), json(base64(24, (byte) -1)),
            json("f/////////8AAAAAAAAAAAAAAAAAAAAA"), json("gAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAA"),
            json(base64(24, (byte) 0).replace('A', '-')),
            "1", "true", "{\"a\":1}", "[1]", "null");

    static final List<String> LISTS = List.of("tools/list", "resources/list", "resources/templates/list",
            "prompts/list");

    static List<Req> sameAnswerCases() {
        List<Req> cases = new ArrayList<>();
        for (Era era : Era.values()) {
            // F1: a body that is not a JSON object.
            for (String body : List.of("\"x\"", "1", "true", "", "   ", "{", "hello", "\"" + MARKER + "\"",
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"} x",
                    "[".repeat(1001) + "]".repeat(1001),
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{\"a\":"
                            + "[".repeat(1001) + "]".repeat(1001) + "}}",
                    "\"" + "y".repeat(20_000) + "\"")) {
                cases.add(req("F1 body " + abbreviated(body), era, "tools/list", body));
            }
            byte[] badUtf8 = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"x\":\"ÿ\"}"
                    .getBytes(StandardCharsets.ISO_8859_1);
            cases.add(new Req("F1 invalid UTF-8 [" + era + "]", era, "application/json", List.of(ACCEPT),
                    eraHeaders(era, "tools/list"), badUtf8));
            Req multipart = req("F1 multipart", era, "tools/list", list(era, "tools/list", "null"));
            cases.add(new Req(multipart.name(), era, "multipart/form-data; boundary=xyz", multipart.accepts(),
                    multipart.headers(), multipart.body()));
            // Served: a byte order mark and a repeated key are still one object.
            byte[] bom = ("﻿" + list(era, "tools/list", "null")).getBytes(StandardCharsets.UTF_8);
            cases.add(new Req("served: tools/list after a BOM [" + era + "]", era, "application/json",
                    List.of(ACCEPT), eraHeaders(era, "tools/list"), bom));
            cases.add(req("served: a repeated key", era, "tools/list",
                    "{\"jsonrpc\":\"2.0\",\"id\":6,\"id\":7,\"method\":\"tools/list\",\"params\":"
                            + params(era, "") + "}"));

            // F2: an Accept without both types, whatever the body.
            Req good = req("F2 tools/list", era, "tools/list", list(era, "tools/list", "null"));
            // One Accept line each (java.net.http sends one line per header
            // name): two lines are compared over a socket, below.
            for (List<String> a : List.of(List.<String>of(), List.of("application/json"),
                    List.of("text/event-stream"), List.of("text/html"), List.of("text/" + MARKER))) {
                cases.add(accepts(good, a));
            }
            cases.add(accepts(good, List.of("text/event-stream;q=0.5, application/json"))); // served
            cases.add(accepts(req("F2 and F1", era, "tools/list", "\"x\""), List.of("application/json")));
            cases.add(accepts(req("F2 and body null", era, "tools/list", "null"), List.of("application/json")));
            cases.add(accepts(req("F2 and a cursor", era, "tools/list", list(era, "tools/list", json("x"))),
                    List.of("text/html")));
            cases.add(accepts(req("F2 and a cancel", era, "notifications/cancelled",
                    cancel(era, "\"requestId\":99")), List.of("application/json")));

            // F3: a cursor the extension does not decode, in its four lists.
            for (String method : LISTS) {
                cases.add(req("F3 " + method + " cursor x", era, method, list(era, method, json("x"))));
            }
            for (String cursor : CURSORS) {
                cases.add(req("F3 tools/list cursor " + abbreviated(cursor), era, "tools/list",
                        list(era, "tools/list", cursor)));
            }
            cases.add(req("F3 without id", era, "tools/list",
                    request(era, null, "tools/list", "\"cursor\":" + json("x"))));
            cases.add(req("F3 with id null", era, "tools/list",
                    request(era, "null", "tools/list", "\"cursor\":" + json("x"))));
            cases.add(req("F3 with a string id", era, "prompts/list",
                    request(era, "\"p-1\"", "prompts/list", "\"cursor\":" + json("x"))));
            // Not the extension's lists: skills/list is answered by the
            // application, tools/call never reads a cursor.
            cases.add(req("skills/list with a cursor", era, "skills/list",
                    request(era, 7, "skills/list", "\"cursor\":" + json("x"))));
            Req call = req("tools/call with a cursor", era, "tools/call", request(era, 7, "tools/call",
                    "\"name\":\"sw_get\",\"arguments\":{\"resource\":\"PEOPLE\",\"id\":1},\"cursor\":"
                            + json("x")));
            cases.add(era == Era.STATELESS ? with(call, "Mcp-Name", "sw_get") : call);

            // F4: a cancel the extension reaches matches nothing in progress.
            for (String members : List.of("\"requestId\":99", "\"requestId\":\"" + MARKER + "\"",
                    "\"requestId\":{\"a\":\"" + MARKER + "\"}", "\"requestId\":[1]", "\"requestId\":null",
                    "", "\"requestId\":99,\"reason\":\"" + MARKER + "\"")) {
                cases.add(req("F4 cancel {" + members + "}", era, "notifications/cancelled",
                        cancel(era, members)));
            }
            for (String id : List.of("5", "\"c-5\"", "null")) {
                cases.add(req("F4 cancel with id " + id, era, "notifications/cancelled",
                        request(era, id, "notifications/cancelled", "\"requestId\":99")));
            }
            cases.add(req("F4 cancel without params", era, "notifications/cancelled",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}"));
            // A cancel with another defect keeps the answer of that defect.
            cases.add(req("F4 cancel with jsonrpc 1.0", era, "notifications/cancelled",
                    cancel(era, "\"requestId\":99").replace("\"2.0\"", "\"1.0\"")));
            cases.add(with(req("F4 cancel", era, "notifications/cancelled", cancel(era, "\"requestId\":99")),
                    "MCP-Protocol-Version", "1999-01-01"));
            cases.add(with(req("F3", era, "tools/list", list(era, "tools/list", json("x"))),
                    "MCP-Protocol-Version", "1999-01-01"));
        }
        // Stateless only: the envelope the extension checks first.
        Era s = Era.STATELESS;
        cases.add(with(req("F4 cancel", s, "notifications/cancelled", cancel(s, "\"requestId\":99")),
                "Mcp-Method", null));
        cases.add(req("F4 cancel without _meta", s, "notifications/cancelled",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":99}}"));
        cases.add(with(req("F4 cancel", s, "notifications/cancelled", cancel(s, "\"requestId\":99")),
                "MCP-Protocol-Version", null));
        cases.add(with(req("F3", s, "tools/list", list(s, "tools/list", json("x"))), "Mcp-Method", "prompts/list"));
        cases.add(with(req("F3", s, "tools/list", list(s, "tools/list", json("x"))), "MCP-Protocol-Version", null));
        // A log level the extension does not know, in the stateless _meta: its
        // 400, before any method. In a session the extension does not read it.
        for (String level : List.of("\"bogus\"", "7", "\"" + MARKER + "\"")) {
            for (Era era : Era.values()) {
                String meta = McpMalformedRequestTest.meta(era, "\"io.modelcontextprotocol/logLevel\":" + level);
                cases.add(req("F3 with logLevel " + level, era, "tools/list",
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{" + meta
                                + ",\"cursor\":\"x\"}}"));
                cases.add(req("F4 cancel with logLevel " + level, era, "notifications/cancelled",
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{" + meta
                                + ",\"requestId\":99}}"));
            }
            String meta = McpMalformedRequestTest.meta(s, "\"io.modelcontextprotocol/logLevel\":" + level);
            // 2.17.1 refused this tools/call itself (name 1) with 200; the
            // extension refuses the log level first, with 400.
            cases.add(with(req("tools/call name 1 with logLevel " + level, s, "tools/call",
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" + meta
                            + ",\"name\":1,\"arguments\":{}}}"), "Mcp-Name", "1"));
            cases.add(req("tools/list id true with logLevel " + level, s, "tools/list",
                    "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"tools/list\",\"params\":{" + meta + "}}"));
        }
        // A protocol version in the stateless _meta that is not a string: the
        // extension reads it as text, a header mismatch (400), before the
        // cancel or the cursor (found by comparing McpRequestMutationTest's
        // requests with 2.17.1).
        for (String version : List.of("1", "true", "{}", "[\"x\"]")) {
            String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":" + version
                    + ",\"io.modelcontextprotocol/clientCapabilities\":{}}";
            cases.add(req("F4 cancel with _meta protocolVersion " + version, s, "notifications/cancelled",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{" + meta
                            + ",\"requestId\":99}}"));
            cases.add(req("F3 with _meta protocolVersion " + version, s, "tools/list",
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{" + meta
                            + ",\"cursor\":\"x\"}}"));
        }
        cases.add(req("served: tools/list with logLevel debug", s, "tools/list",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{"
                        + McpMalformedRequestTest.meta(s, "\"io.modelcontextprotocol/logLevel\":\"DEBUG\"") + "}}"));
        return cases;
    }

    static String abbreviated(String s) {
        return s.length() <= 24 ? s : s.substring(0, 20) + "...(" + s.length() + ")";
    }

    // ----------------------------------------------------------------- HTTP

    private HttpRequest build(URI target, Req r, String sessionId) {
        HttpRequest.Builder b = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", r.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(r.body()));
        for (String a : r.accepts()) {
            b.header("Accept", a);
        }
        r.headers().forEach(b::header);
        if (r.era() == Era.SESSION) {
            b.header("Mcp-Session-Id", sessionId);
        }
        return b.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    private String openSession() throws Exception {
        HttpResponse<String> init = send(HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize",
                         "params":{"protocolVersion":"%s","capabilities":{},
                                   "clientInfo":{"name":"log-guard-test","version":"1.0"}}}""".formatted(SESSION_ERA)))
                .build());
        assertNotNull(init, "no answer to initialize");
        assertEquals(200, init.statusCode(), init.body());
        return init.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    /** What the comparison looks at: status, Content-Type, Mcp-Session-Id present, the JSON body (or the text). */
    record Seen(int status, String contentType, boolean sessionHeader, Object body) {
        static Seen of(HttpResponse<String> r) {
            String type = r.headers().firstValue("Content-Type").orElse(null);
            Object body;
            if (r.body().isEmpty()) {
                body = "";
            } else {
                JsonObject m = McpRequestMutationTest.message(r);
                body = m != null ? m : r.body();
            }
            return new Seen(r.statusCode(), type, r.headers().firstValue("Mcp-Session-Id").isPresent(), body);
        }
    }

    private List<LogRecord> during(int from) {
        return new ArrayList<>(records.subList(from, records.size()));
    }

    static String describe(LogRecord rec) {
        return rec.getLevel() + " " + rec.getLoggerName() + ": " + McpMalformedRequestTest.formatted(rec)
                + (rec.getThrown() == null ? "" : " " + rec.getThrown());
    }

    // ----------------------------------------------------------------- tests

    @Test
    void controlTheRouteReachesTheExtension() throws Exception {
        HttpResponse<String> r = send(build(mcp.resolve(PATH), req("tools/list", Era.AUTO_INIT, "tools/list",
                list(Era.AUTO_INIT, "tools/list", "null")), session));
        assertNotNull(r, "no answer");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("sw_get"), "the test route no longer reaches the extension: " + r.body());
    }

    // The guard. If one of these fails, the extension changed what it
    // answers or what it logs: read StreamableHttpMcpMessageHandler.handle
    // (the Accept, the parse), Messages.getCursor with Cursor.decode, and
    // McpMessageHandler.cancelRequest again.
    @Test
    void theExtensionLogsTheseRefusalsAtEveryRequest() throws Exception {
        record Family(String name, Req request, int status, String contentType, String body, String logger,
                      String line) {
        }
        Era a = Era.AUTO_INIT;
        List<Family> families = List.of(
                new Family("F1", req("body \"x\"", a, null, "\"x\""), 200, "application/json", PARSE_ERROR,
                        "io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler",
                        "Unable to parse the JSON message"),
                new Family("F2", accepts(req("tools/list", a, null, list(a, "tools/list", "null")),
                        List.of("application/json")), 400, null, "",
                        "io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler",
                        "Invalid Accept header"),
                new Family("F3", req("cursor x", a, null, list(a, "tools/list", json("x"))), 200,
                        "application/json", """
                        {"jsonrpc":"2.0","id":7,"error":{"code":-32602,"message":"Invalid cursor detected: x"}}""",
                        "io.quarkiverse.mcp.server.runtime.Messages", "Invalid cursor detected"),
                new Family("F4", req("cancel", a, null, cancel(a, "\"requestId\":99")), 202, null, "",
                        "io.quarkiverse.mcp.server.runtime.McpMessageHandler",
                        "Ignored unknown/completed/invalid cancel request"),
                new Family("F4 with id", req("cancel with id", a, null,
                        request(a, 5, "notifications/cancelled", "\"requestId\":99")), 200, null, "",
                        "io.quarkiverse.mcp.server.runtime.McpMessageHandler",
                        "Ignored unknown/completed/invalid cancel request"));
        for (Family f : families) {
            int from = records.size();
            for (int i = 0; i < 2; i++) {
                HttpResponse<String> r = send(build(mcp.resolve(PATH), f.request(), session));
                assertNotNull(r, f.name() + ": no answer");
                String what = f.name() + ": the extension answered " + r.statusCode() + " " + r.headers().map()
                        + " " + r.body();
                assertEquals(f.status(), r.statusCode(), what);
                assertEquals(f.contentType(), r.headers().firstValue("Content-Type").orElse(null), what);
                assertTrue(r.headers().firstValue("Mcp-Session-Id").isEmpty(), what);
                if (f.body().isEmpty()) {
                    assertEquals("", r.body(), what);
                } else {
                    assertEquals(new JsonObject(f.body()), new JsonObject(r.body()), what);
                }
            }
            long lines = during(from).stream()
                    .filter(rec -> rec.getLevel().intValue() == Level.WARNING.intValue()
                            && f.logger().equals(rec.getLoggerName())
                            && String.valueOf(McpMalformedRequestTest.formatted(rec)).startsWith(f.line()))
                    .count();
            assertEquals(2, lines, f.name() + ": the extension no longer logs \"" + f.line()
                    + "\" at every request; it logged " + during(from).stream().map(McpExtensionLogGuardTest::describe)
                    .toList());
        }
    }

    @Test
    void theFilterWritesTheAnswerOfTheExtensionAndLogsNoLinePerRequest() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Req r : sameAnswerCases()) {
            HttpResponse<String> ext = send(build(mcp.resolve(PATH), r, session));
            int from = records.size();
            HttpResponse<String> mine = send(build(mcp, r, session));
            List<LogRecord> logged = during(from);
            if (ext == null || mine == null) {
                failures.add(r.name() + ": no answer (" + (ext == null ? "extension" : "/mcp") + ")");
                continue;
            }
            Seen e = Seen.of(ext);
            Seen m = Seen.of(mine);
            if (!e.equals(m)) {
                failures.add(r.name() + ":\n  extension " + e + "\n  /mcp      " + m);
            }
            for (LogRecord rec : logged) {
                String text = String.valueOf(McpMalformedRequestTest.formatted(rec));
                boolean filter = McpTransportFilter.class.getName().equals(rec.getLoggerName());
                if (rec.getLevel().intValue() >= Level.WARNING.intValue() && (!filter || rec.getThrown() != null)) {
                    failures.add(r.name() + ": /mcp logged " + describe(rec));
                } else if (text.contains(MARKER) || text.contains("bogus")) {
                    failures.add(r.name() + ": the request's text in the log: " + describe(rec));
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " differences:\n" + String.join("\n", failures));
    }

    // Two Accept lines: the extension reads them all (getAll), so one type on
    // each line is the transport's Accept, and two wrong lines are not.
    // java.net.http sends one line per name, hence a socket.
    @Test
    void twoAcceptLinesGetTheAnswerOfTheExtension() throws Exception {
        String body = list(Era.AUTO_INIT, "tools/list", "null");
        List<String> failures = new ArrayList<>();
        for (List<String> accepts : List.of(List.of("application/json", "text/event-stream"),
                List.of("text/html", "text/" + MARKER), List.of("application/json", "application/json"))) {
            StringBuilder head = new StringBuilder();
            accepts.forEach(a -> head.append("Accept: ").append(a).append("\r\n"));
            Map<String, RawHttp.Exchange> seen = new LinkedHashMap<>();
            int from = 0;
            for (String path : List.of(PATH, "/mcp")) {
                from = records.size();
                seen.put(path, RawHttp.exchange(mcp.getPort(), RawHttp.ascii("POST " + path + " HTTP/1.1\r\n"
                        + "Host: localhost\r\nContent-Type: application/json\r\n" + head
                        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                        + "Connection: close\r\n\r\n" + body), false, 5000));
            }
            RawHttp.Exchange e = seen.get(PATH);
            RawHttp.Exchange m = seen.get("/mcp");
            String what = accepts + ": extension " + e.head() + " " + e.body() + "\n  /mcp " + m.head() + " "
                    + m.body();
            boolean sameBody = e.body().isEmpty() ? m.body().isEmpty()
                    : !m.body().isEmpty() && new JsonObject(e.body()).equals(new JsonObject(m.body()));
            if (e.status() != m.status() || !java.util.Objects.equals(e.header("Content-Type"), m.header("Content-Type"))
                    || (e.header("Mcp-Session-Id") == null) != (m.header("Mcp-Session-Id") == null) || !sameBody) {
                failures.add(what);
            }
            for (LogRecord rec : during(from)) {
                if (rec.getLevel().intValue() >= Level.WARNING.intValue()
                        && !McpTransportFilter.class.getName().equals(rec.getLoggerName())
                        || String.valueOf(McpMalformedRequestTest.formatted(rec)).contains(MARKER)) {
                    failures.add(accepts + ": /mcp logged " + describe(rec));
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
