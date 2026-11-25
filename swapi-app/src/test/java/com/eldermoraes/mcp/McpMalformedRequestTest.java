package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkiverse.mcp.server.runtime.McpMessageHandler;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.eldermoraes.mcp.Within.within;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.17.1: the MCP extension (quarkus-mcp-server 2.1.0) reads request fields
 * with unchecked casts. A malformed request either got HTTP 500 with a stack
 * trace in the log, or never got an answer at all and left state behind in
 * the extension until a restart: an entry in its set of ongoing requests,
 * the throwaway session of auto-init and an open request context.
 * McpTransportFilter refuses those shapes itself, before the extension.
 *
 * Every shape is sent in the three ways a client reaches /mcp: a session
 * request without headers (auto-init creates a throwaway session), a request
 * in a genuine session (initialize, then its Mcp-Session-Id) and a stateless
 * request (MCP-Protocol-Version 2026-07-28, Mcp-Method and, for tools/call and
 * prompts/get, Mcp-Name). For each one the test asserts an answer within the
 * deadline, its status and JSON-RPC error, and that nothing was left in the
 * extension: the size of its set of ongoing requests and the number of
 * sessions in its ConnectionManager are the same before and after.
 *
 * The status follows the nearest answer the extension gives itself: always
 * 200 in the session era; in the stateless era 400 for a defect of the
 * envelope (the body, params, _meta), as the extension answers a _meta it
 * refuses, and 200 for a defect of a method's own fields, as it answers an
 * unknown tool name. A body shaped like a response gets 202 without a body:
 * this server never sends a request to a client, so no response is awaited.
 */
@QuarkusTest
class McpMalformedRequestTest {

    static final String STATELESS = "2026-07-28";
    static final String SESSION_ERA = "2025-11-25";
    private static final long DEADLINE_MS = 3000;
    private static final int REPEAT = 3;

    enum Era { AUTO_INIT, SESSION, STATELESS }

    /** What a request of one shape must get in one era; null code = 202 without a body. */
    record Expect(int status, Integer code) {
        static final Expect ACCEPTED = new Expect(202, null);
        // Left to the extension, which answers it itself: any JSON-RPC
        // answer (or 202) but a 500, and nothing left behind.
        static final Expect EXTENSION = new Expect(-1, null);
    }

    record Shape(String name, String method, String mcpName, Function<Era, String> body,
                 Map<Era, Expect> expected, boolean idNull) {
    }

    @TestHTTPResource("/mcp")
    URI mcp;

    @TestHTTPResource("/api/stats")
    URI stats;

    @Inject
    StreamableHttpMcpMessageHandler extensionHandler;

    @Inject
    ConnectionManager sessions;

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
    void attach() {
        capture.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void detach() {
        Logger.getLogger("").removeHandler(capture);
    }

    // ---------------------------------------------------------------- shapes

    /** The _meta a stateless request needs, with extra members; in a session only the extra members. */
    static String meta(Era era, String extra) {
        if (era == Era.STATELESS) {
            return """
                    "_meta":{"io.modelcontextprotocol/protocolVersion":"%s",
                      "io.modelcontextprotocol/clientCapabilities":{}%s}""".formatted(STATELESS,
                    extra.isEmpty() ? "" : "," + extra);
        }
        return extra.isEmpty() ? "" : "\"_meta\":{" + extra + "}";
    }

    /** params with the era's _meta first, then the given members. */
    static String params(Era era, String members) {
        String m = meta(era, "");
        if (m.isEmpty()) {
            return "{" + members + "}";
        }
        return members.isEmpty() ? "{" + m + "}" : "{" + m + "," + members + "}";
    }

    static Map<Era, Expect> envelope(int code) {
        Map<Era, Expect> m = new EnumMap<>(Era.class);
        m.put(Era.AUTO_INIT, new Expect(200, code));
        m.put(Era.SESSION, new Expect(200, code));
        m.put(Era.STATELESS, new Expect(400, code));
        return m;
    }

    static Map<Era, Expect> field(int code) {
        Map<Era, Expect> m = new EnumMap<>(Era.class);
        for (Era era : Era.values()) {
            m.put(era, new Expect(200, code));
        }
        return m;
    }

    static Map<Era, Expect> sessionOnly(Map<Era, Expect> m) {
        m.remove(Era.STATELESS);
        return m;
    }

    static Map<Era, Expect> accepted() {
        return all(Expect.ACCEPTED);
    }

    static Map<Era, Expect> extension() {
        return all(Expect.EXTENSION);
    }

    static Map<Era, Expect> all(Expect expect) {
        Map<Era, Expect> m = new EnumMap<>(Era.class);
        for (Era era : Era.values()) {
            m.put(era, expect);
        }
        return m;
    }

    static Shape shape(String name, String method, String mcpName, Function<Era, String> body,
                       Map<Era, Expect> expected) {
        return new Shape(name, method, mcpName, body, expected, false);
    }

    static Shape shapeWithoutId(String name, String method, Function<Era, String> body, Map<Era, Expect> expected) {
        return new Shape(name, method, null, body, expected, true);
    }

    static String clientInfoInMeta(Era era, String clientInfo) {
        return "{" + meta(era, "\"io.modelcontextprotocol/clientInfo\":" + clientInfo) + "}";
    }

    static String toolsListWithMeta(Era era, String extra) {
        return """
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{%s}}""".formatted(meta(era, extra));
    }

    static String initialize(String params) {
        return """
                {"jsonrpc":"2.0","id":7,"method":"initialize","params":%s}""".formatted(params);
    }

    static String complete(Era era, String members) {
        return """
                {"jsonrpc":"2.0","id":7,"method":"completion/complete","params":%s}"""
                .formatted(params(era, members));
    }

    static final String PROMPT_REF = "\"ref\":{\"type\":\"ref/prompt\",\"name\":\"character_profile\"}";

    static List<Shape> shapes() {
        List<Shape> s = new ArrayList<>();
        // The body itself.
        s.add(new Shape("body null", "tools/list", null, era -> "null", envelope(-32600), true));
        // params that is not an object, on any method.
        for (String p : List.of("[]", "\"x\"", "1", "true")) {
            s.add(shape("params " + p, "tools/list", null, era -> """
                    {"jsonrpc":"2.0","id":7,"method":"tools/list","params":%s}""".formatted(p), envelope(-32602)));
        }
        s.add(shapeWithoutId("notification with params []", "notifications/initialized", era -> """
                {"jsonrpc":"2.0","method":"notifications/initialized","params":[]}""", envelope(-32602)));
        // server/discover is stateless in every era, as the extension takes it.
        Map<Era, Expect> discover = new EnumMap<>(Era.class);
        for (Era era : Era.values()) {
            discover.put(era, new Expect(400, -32602));
        }
        s.add(shape("server/discover with params []", "server/discover", null, era -> """
                {"jsonrpc":"2.0","id":7,"method":"server/discover","params":[]}""", discover));
        // _meta that is not an object.
        for (String m : List.of("\"x\"", "[]")) {
            s.add(shape("_meta " + m, "tools/list", null, era -> """
                    {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"_meta":%s}}""".formatted(m),
                    envelope(-32602)));
        }
        // What _meta says about the client.
        s.add(shape("_meta clientCapabilities \"x\"", "tools/list", null, era -> era == Era.STATELESS
                ? """
                  {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"_meta":{
                    "io.modelcontextprotocol/protocolVersion":"%s",
                    "io.modelcontextprotocol/clientCapabilities":"x"}}}""".formatted(STATELESS)
                : toolsListWithMeta(era, "\"io.modelcontextprotocol/clientCapabilities\":\"x\""),
                envelope(-32602)));
        for (String info : List.of("\"x\"", "{}", "{\"name\":\"n\"}", "{\"version\":\"1\"}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":\"x\"}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":[1]}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":[{}]}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":[{\"src\":\"a\",\"sizes\":\"x\"}]}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":[{\"src\":\"a\",\"sizes\":[null]}]}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":[{\"src\":\"a\",\"theme\":\"blue\"}]}",
                "{\"name\":\"n\",\"version\":\"1\",\"icons\":[{\"src\":\"a\",\"theme\":null}]}")) {
            s.add(shape("_meta clientInfo " + info, "tools/list", null,
                    era -> toolsListWithMeta(era, "\"io.modelcontextprotocol/clientInfo\":" + info),
                    envelope(-32602)));
        }
        // initialize: only the session era decodes it (a stateless one is
        // refused by the extension as a method of the session era).
        String caps = "\"capabilities\":{}";
        String info = "\"clientInfo\":{\"name\":\"malformed-test\",\"version\":\"1.0\"}";
        String version = "\"protocolVersion\":\"" + SESSION_ERA + "\"";
        for (Map.Entry<String, String> e : Map.of(
                "initialize without clientInfo", "{" + version + "," + caps + "}",
                "initialize with clientInfo null", "{" + version + "," + caps + ",\"clientInfo\":null}",
                "initialize with clientInfo \"x\"", "{" + version + "," + caps + ",\"clientInfo\":\"x\"}",
                "initialize with clientInfo {}", "{" + version + "," + caps + ",\"clientInfo\":{}}",
                "initialize with clientInfo icons \"x\"", "{" + version + "," + caps
                        + ",\"clientInfo\":{\"name\":\"n\",\"version\":\"1\",\"icons\":\"x\"}}",
                "initialize with capabilities []", "{" + version + "," + info + ",\"capabilities\":[]}",
                "initialize with capabilities \"x\"", "{" + version + "," + info + ",\"capabilities\":\"x\"}")
                .entrySet()) {
            s.add(shape(e.getKey(), "initialize", null, era -> initialize(e.getValue()),
                    sessionOnly(field(-32602))));
        }
        // tools/call and prompts/get.
        s.add(shape("tools/call without name", "tools/call", "sw_get", era -> """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":%s}"""
                .formatted(params(era, "\"arguments\":{}")), field(-32602)));
        s.add(shape("tools/call with name null", "tools/call", "sw_get", era -> """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":%s}"""
                .formatted(params(era, "\"name\":null,\"arguments\":{}")), field(-32602)));
        for (String a : List.of("[]", "\"x\"", "1")) {
            s.add(shape("tools/call with arguments " + a, "tools/call", "sw_get", era -> """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":%s}"""
                    .formatted(params(era, "\"name\":\"sw_get\",\"arguments\":" + a)), field(-32602)));
        }
        s.add(shape("prompts/get without name", "prompts/get", "character_profile", era -> """
                {"jsonrpc":"2.0","id":7,"method":"prompts/get","params":%s}"""
                .formatted(params(era, "")), field(-32602)));
        for (String a : List.of("[]", "\"x\"")) {
            s.add(shape("prompts/get with arguments " + a, "prompts/get", "character_profile", era -> """
                    {"jsonrpc":"2.0","id":7,"method":"prompts/get","params":%s}"""
                    .formatted(params(era, "\"name\":\"character_profile\",\"arguments\":" + a)),
                    field(-32602)));
        }
        // completion/complete.
        s.add(shape("completion/complete with ref \"x\"", "completion/complete", null,
                era -> complete(era, "\"ref\":\"x\",\"argument\":{\"name\":\"name\",\"value\":\"V\"}"),
                field(-32602)));
        s.add(shape("completion/complete with argument \"x\"", "completion/complete", null,
                era -> complete(era, PROMPT_REF + ",\"argument\":\"x\""), field(-32602)));
        s.add(shape("completion/complete with argument without name", "completion/complete", null,
                era -> complete(era, PROMPT_REF + ",\"argument\":{\"value\":\"V\"}"), field(-32602)));
        s.add(shape("completion/complete with argument without value", "completion/complete", null,
                era -> complete(era, PROMPT_REF + ",\"argument\":{\"name\":\"name\"}"), field(-32602)));
        s.add(shape("tools/call with name 5", "tools/call", "5", era -> """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":%s}"""
                .formatted(params(era, "\"name\":5,\"arguments\":{}")), field(-32602)));
        // Left to the extension, which answers them itself and keeps nothing:
        // checked here so that a change of the filter that starts to send
        // them on a path that fails is seen.
        s.add(shapeWithoutId("notifications/cancelled with requestId {}", "notifications/cancelled", era -> """
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":%s}"""
                .formatted(params(era, "\"requestId\":{}")), extension()));
        s.add(shape("resources/read with uri {}", "resources/read", "{}", era -> """
                {"jsonrpc":"2.0","id":7,"method":"resources/read","params":%s}"""
                .formatted(params(era, "\"uri\":{}")), extension()));
        s.add(shape("skills/get with uri {}", "skills/get", null, era -> """
                {"jsonrpc":"2.0","id":7,"method":"skills/get","params":%s}"""
                .formatted(params(era, "\"uri\":{}")), extension()));
        s.add(shape("completion/complete without ref", "completion/complete", null,
                era -> complete(era, "\"argument\":{\"name\":\"name\",\"value\":\"V\"}"), extension()));
        s.add(shape("tools/call without params", "tools/call", "sw_get",
                era -> "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"}", sessionOnly(extension())));
        s.add(shape("tools/list with jsonrpc 1.0 and params []", "tools/list", null, era -> """
                {"jsonrpc":"1.0","id":7,"method":"tools/list","params":[]}""", envelope(-32602)));
        s.add(shape("tools/call without name and jsonrpc 1.0", "tools/call", "sw_get", era -> """
                {"jsonrpc":"1.0","id":7,"method":"tools/call","params":%s}"""
                .formatted(params(era, "\"arguments\":{}")), extension()));
        // A body shaped like a response.
        for (String r : List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":{},\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-1,\"message\":\"x\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"result\":{}}")) {
            s.add(new Shape("response " + r, null, null, era -> r, accepted(), false));
        }
        return s;
    }

    // ------------------------------------------------------------------ HTTP

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest request(Shape shape, Era era, String session) {
        HttpRequest.Builder b = post(shape.body().apply(era));
        switch (era) {
            case AUTO_INIT -> {
            }
            case SESSION -> b.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", SESSION_ERA);
            case STATELESS -> {
                b.header("MCP-Protocol-Version", STATELESS);
                if (shape.method() != null) {
                    b.header("Mcp-Method", shape.method());
                }
                if (shape.mcpName() != null) {
                    b.header("Mcp-Name", shape.mcpName());
                }
            }
        }
        return b.build();
    }

    /** Sends and waits for the WHOLE body; null if it did not end within the deadline. */
    static HttpResponse<String> sendWithDeadline(HttpRequest request, long deadlineMs) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                return future.get(deadlineMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                client.shutdownNow();
                return null;
            }
        }
    }

    String openSession() throws Exception {
        HttpResponse<String> init = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"%s","capabilities":{},
                           "clientInfo":{"name":"malformed-test","version":"1.0"}}}
                """.formatted(SESSION_ERA)).build(), 5000);
        assertEquals(200, init.statusCode(), init.body());
        return init.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    // ----------------------------------------------------------------- state

    // The extension's set of requests in progress, one entry per request id
    // and connection, added before the method runs and removed when it ends.
    // A private field of McpMessageHandler (2.1.0), read by reflection: there
    // is no accessor. If an upgrade renames or removes it, this fails loudly
    // instead of comparing nothing.
    static Set<?> ongoingRequests(StreamableHttpMcpMessageHandler handler) {
        try {
            Field f = McpMessageHandler.class.getDeclaredField("ongoingRequests");
            f.setAccessible(true);
            return (Set<?>) f.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("McpMessageHandler.ongoingRequests is gone: the extension changed, read its"
                    + " source again and find where it keeps the requests in progress", e);
        }
    }

    static int connections(ConnectionManager sessions) {
        int n = 0;
        for (var ignored : sessions) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ test

    @Test
    void everyMalformedShapeIsAnsweredAtOnceAndLeavesNoState() throws Exception {
        String session = openSession();
        Set<?> ongoing = ongoingRequests(extensionHandler);
        List<String> failures = new ArrayList<>();
        List<String> observed = new ArrayList<>();
        records.clear();
        for (Shape shape : shapes()) {
            for (Map.Entry<Era, Expect> e : shape.expected().entrySet()) {
                Era era = e.getKey();
                Expect expect = e.getValue();
                String what = shape.name() + " [" + era + "]";
                int ongoingBefore = ongoing.size();
                int connectionsBefore = connections(sessions);
                String seen = null;
                for (int i = 0; i < REPEAT; i++) {
                    long start = System.nanoTime();
                    HttpResponse<String> r = sendWithDeadline(request(shape, era, session), DEADLINE_MS);
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    if (r == null) {
                        seen = "no answer within " + DEADLINE_MS + " ms";
                        failures.add(what + ": " + seen);
                        break;
                    }
                    String body = r.body();
                    seen = r.statusCode() + " " + body.substring(0, Math.min(160, body.length())) + " (" + ms + " ms)";
                    String problem = check(shape, expect, r);
                    if (problem != null) {
                        failures.add(what + ": " + problem + "; got " + seen);
                        break;
                    }
                }
                // The extension removes an auto-init session and the ongoing
                // entry just after it answers, so give it a moment.
                boolean clean = within(2000, () -> ongoing.size() == ongoingBefore
                        && connections(sessions) == connectionsBefore);
                String state = "ongoing " + ongoingBefore + " -> " + ongoing.size()
                        + ", sessions " + connectionsBefore + " -> " + connections(sessions);
                if (!clean) {
                    failures.add(what + ": state left behind, " + state);
                }
                observed.add(what + " => " + seen + " | " + state);
            }
        }
        List<String> errors = records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .map(r -> r.getLoggerName() + ": " + r.getMessage()
                        + (r.getThrown() == null ? "" : " " + r.getThrown()))
                .distinct()
                .toList();
        if (!errors.isEmpty()) {
            failures.add("ERROR records in the log: " + errors);
        }
        // The refusals log one WARN line per kind per minute, with no stack
        // trace. (A kind may already have been logged by an earlier test in
        // this minute, so "at most one", not "exactly one".)
        Map<String, Long> warnings = records.stream()
                .filter(r -> McpTransportFilter.class.getName().equals(r.getLoggerName()))
                .peek(r -> {
                    if (r.getThrown() != null) {
                        failures.add("a stack trace in the log: " + r.getThrown());
                    }
                })
                .collect(java.util.stream.Collectors.groupingBy(McpMalformedRequestTest::formatted,
                        java.util.stream.Collectors.counting()));
        warnings.forEach((line, count) -> {
            if (count > 1) {
                failures.add(count + " log lines of one kind within a minute: " + line);
            }
        });
        assertTrue(failures.isEmpty(), String.join("\n", failures)
                + "\n\nobserved:\n" + String.join("\n", observed));
    }

    static String formatted(LogRecord record) {
        return record instanceof org.jboss.logmanager.ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage();
    }

    private static String check(Shape shape, Expect expect, HttpResponse<String> r) {
        if (expect == Expect.EXTENSION) {
            if (r.statusCode() >= 500) {
                return "status " + r.statusCode();
            }
            if (r.statusCode() == 202 && r.body().isEmpty()) {
                return null;
            }
            try {
                return "2.0".equals(new JsonObject(r.body()).getValue("jsonrpc")) ? null : "not JSON-RPC";
            } catch (RuntimeException e) {
                return "not a JSON object";
            }
        }
        if (r.statusCode() != expect.status()) {
            return "status " + r.statusCode() + ", expected " + expect.status();
        }
        if (expect.code() == null) {
            return r.body().isEmpty() ? null : "a body where none was expected";
        }
        if (!r.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
            return "Content-Type " + r.headers().firstValue("Content-Type").orElse("none");
        }
        JsonObject body;
        try {
            body = new JsonObject(r.body());
        } catch (RuntimeException e) {
            return "not a JSON object";
        }
        JsonObject error = body.getValue("error") instanceof JsonObject o ? o : null;
        if (error == null || !Integer.valueOf(expect.code()).equals(error.getValue("code"))) {
            return "error code " + (error == null ? "none" : error.getValue("code")) + ", expected " + expect.code();
        }
        if (!"2.0".equals(body.getValue("jsonrpc"))) {
            return "not JSON-RPC 2.0";
        }
        Object id = body.getValue("id");
        if (shape.idNull() ? !(body.containsKey("id") && id == null) : !Integer.valueOf(7).equals(id)) {
            return "id " + id;
        }
        if (!(error.getValue("message") instanceof String m) || m.isBlank()) {
            return "no message";
        }
        return null;
    }

    // Every revision from 2024-11-05 to 2025-11-25 requires capabilities, but
    // the extension reads a missing one as "no capabilities" and opens the
    // session: a request it serves is not refused here.
    @Test
    void initializeWithoutCapabilitiesIsStillServed() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post(initialize("""
                {"protocolVersion":"%s","clientInfo":{"name":"malformed-test","version":"1.0"}}"""
                .formatted(SESSION_ERA))).build(), DEADLINE_MS);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(SESSION_ERA, new JsonObject(r.body()).getJsonObject("result").getString("protocolVersion"));
        String session = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
        sendWithDeadline(HttpRequest.newBuilder(mcp).header("Mcp-Session-Id", session).DELETE().build(), DEADLINE_MS);
    }

    // The deadline McpTransportFilter gives every POST it passes on to the
    // extension (McpResponseDeadlineTest shows what it does). No answer of
    // this server takes more than milliseconds; ten seconds covers a slow
    // machine without holding a connection for long. A future operation that
    // legitimately takes longer needs an explicit exception, not a larger
    // value here.
    @Test
    void responseDeadlineIsTenSeconds() {
        assertEquals(Duration.ofSeconds(10), org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("swapi.mcp.response-deadline", Duration.class));
    }

    // A refused request is one HTTP request in the /mcp group of /stats.
    @Test
    void aRefusedRequestIsCountedOnceAsMcp() throws Exception {
        long before = mcpCount();
        HttpResponse<String> r = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":[]}""").build(), DEADLINE_MS);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(-32602, new JsonObject(r.body()).getJsonObject("error").getInteger("code"), r.body());
        assertEquals(before + 1, mcpCount());
    }

    private long mcpCount() {
        Number n = given().get(stats).then().statusCode(200).extract().path("routes.'/mcp'");
        return n == null ? 0 : n.longValue();
    }
}
