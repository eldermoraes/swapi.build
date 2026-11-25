package com.eldermoraes.mcp;

import com.eldermoraes.mcp.McpMalformedRequestTest.Era;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.eldermoraes.mcp.McpMalformedRequestTest.SESSION_ERA;
import static com.eldermoraes.mcp.McpMalformedRequestTest.STATELESS;
import static com.eldermoraes.mcp.McpMalformedRequestTest.connections;
import static com.eldermoraes.mcp.McpMalformedRequestTest.ongoingRequests;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The net under McpMalformedRequestTest (2.17.1): that matrix lists the
 * shapes a person found by reading the extension's source; this test makes
 * the shapes mechanically, so a shape nobody thought of is found too.
 *
 * It starts from a valid request for every method this server serves, in each
 * era a client reaches /mcp in (auto-init, a genuine session, stateless), each
 * as full as the protocol revisions allow, since a mutation can only touch a
 * member that is there. Every seed must be served first, so a broken seed
 * cannot make the test pass without testing anything. Then every member at
 * every depth (and the body itself) is removed, and replaced by null, a
 * string, a number, a boolean, an empty array, an array and an empty object.
 *
 * Every mutated request must get an HTTP answer at once (well inside the
 * budget of McpMalformedRequestTest), never a 5xx, never an exception that
 * the failure handler of McpTransportFilter had to turn into JSON-RPC and
 * never the answer of its deadline; the body must be a JSON-RPC result or
 * error, or empty with 202/204. No WARN or above may be logged by any logger
 * but McpTransportFilter's (2.17.2, #126: before, a body that is not an
 * object, a cursor that does not decode and a cancel of nothing logged a
 * line per request in the extension), and a seed logs nothing above INFO at
 * all. And nothing may be left in the extension:
 * its requests in progress and its sessions are compared before and after
 * each request. A session that a successful initialize opens is the client's,
 * and is closed with DELETE as a client would close it.
 *
 * Serving a mutation is fine (an ignored member, an optional member removed):
 * the test is about "answered at once, no failure, no state", not strictness.
 * All failures are collected and reported together.
 */
@QuarkusTest
class McpRequestMutationTest {

    // Well inside the per-request budget of McpMalformedRequestTest (3 s): a
    // request of this server is answered in milliseconds.
    private static final long BUDGET_MS = 1000;
    private static final long STATE_SETTLE_MS = 1000;
    private static final int ID = 7;
    private static final String DEADLINE_MESSAGE = "deadline";

    @TestHTTPResource("/mcp")
    URI mcp;

    @Inject
    StreamableHttpMcpMessageHandler extensionHandler;

    @Inject
    ConnectionManager sessions;

    @Inject
    Router router;

    private HttpClient client;
    private Route failureProbe;
    private final List<String> routeFailures = new CopyOnWriteArrayList<>();

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
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        capture.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(capture);
        // Sees every failure of a POST to /mcp before McpTransportFilter's
        // failure handler turns it into JSON-RPC, and passes it on.
        // The extension's own ctx.fail(400/404) is a clean refusal: only an
        // exception or a 5xx counts.
        failureProbe = router.post("/mcp").order(Integer.MIN_VALUE + 1).failureHandler(rc -> {
            if (rc.failure() != null || rc.statusCode() >= 500) {
                routeFailures.add(rc.statusCode() + " " + (rc.failure() == null ? "" : rc.failure().toString()));
            }
            rc.next();
        });
    }

    @AfterEach
    void detach() {
        failureProbe.remove();
        Logger.getLogger("").removeHandler(capture);
        client.shutdownNow();
    }

    // ----------------------------------------------------------------- seeds

    /** A valid request; eras: where it is served. nameMember: the param the stateless Mcp-Name mirrors. */
    record Seed(String name, String method, JsonObject params, boolean notification, Set<Era> eras,
                String nameMember) {
    }

    private static final Set<Era> ALL = EnumSet.allOf(Era.class);
    private static final Set<Era> SESSIONS = EnumSet.of(Era.AUTO_INIT, Era.SESSION);

    // The initial cursor of the extension (Cursor.encode(EPOCH, EPOCH)): a
    // valid cursor, which a list of one page serves like no cursor.
    private static final String INITIAL_CURSOR = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    static JsonObject implementation() {
        return new JsonObject()
                .put("name", "mutation-test")
                .put("version", "1.0")
                .put("title", "Mutation test")
                .put("description", "A client of the tests")
                .put("websiteUrl", "https://example.org")
                .put("icons", new JsonArray().add(new JsonObject()
                        .put("src", "https://example.org/icon.png")
                        .put("mimeType", "image/png")
                        .put("sizes", new JsonArray().add("48x48"))
                        .put("theme", "light")));
    }

    static JsonObject capabilities() {
        return new JsonObject()
                .put("roots", new JsonObject().put("listChanged", true))
                .put("sampling", new JsonObject())
                .put("elicitation", new JsonObject().put("form", new JsonObject()));
    }

    static List<Seed> seeds() {
        List<Seed> s = new ArrayList<>();
        s.add(new Seed("initialize", "initialize", new JsonObject()
                .put("protocolVersion", SESSION_ERA)
                .put("capabilities", capabilities())
                .put("clientInfo", implementation()), false, EnumSet.of(Era.AUTO_INIT), null));
        s.add(new Seed("notifications/initialized", "notifications/initialized", new JsonObject(), true,
                SESSIONS, null));
        s.add(new Seed("ping", "ping", new JsonObject(), false, SESSIONS, null));
        s.add(new Seed("logging/setLevel", "logging/setLevel", new JsonObject().put("level", "info"), false,
                SESSIONS, null));
        s.add(new Seed("server/discover", "server/discover", new JsonObject(), false, EnumSet.of(Era.STATELESS),
                null));
        for (String list : List.of("tools/list", "resources/list", "resources/templates/list", "prompts/list")) {
            s.add(new Seed(list, list, new JsonObject().put("cursor", INITIAL_CURSOR), false, ALL, null));
        }
        s.add(new Seed("tools/call sw_get", "tools/call", new JsonObject().put("name", "sw_get")
                .put("arguments", new JsonObject().put("resource", "PEOPLE").put("id", 1)
                        .put("expand", new JsonArray().add("homeworld"))), false, ALL, "name"));
        s.add(new Seed("tools/call sw_list", "tools/call", new JsonObject().put("name", "sw_list")
                .put("arguments", new JsonObject().put("resource", "FILMS")
                        .put("fields", new JsonArray().add("title"))), false, ALL, "name"));
        s.add(new Seed("tools/call sw_random", "tools/call", new JsonObject().put("name", "sw_random")
                .put("arguments", new JsonObject().put("resource", "PLANETS")), false, ALL, "name"));
        s.add(new Seed("tools/call sw_search", "tools/call", new JsonObject().put("name", "sw_search")
                .put("arguments", new JsonObject().put("resource", "PEOPLE").put("query", "sky")
                        .put("fields", new JsonArray().add("name"))), false, ALL, "name"));
        s.add(new Seed("resources/read", "resources/read", new JsonObject().put("uri", "swapi://people/1"), false,
                ALL, "uri"));
        s.add(new Seed("prompts/get", "prompts/get", new JsonObject().put("name", "character_profile")
                .put("arguments", new JsonObject().put("name", "Luke")), false, ALL, "name"));
        s.add(new Seed("skills/list", "skills/list", new JsonObject(), false, ALL, null));
        s.add(new Seed("skills/get", "skills/get",
                new JsonObject().put("uri", "skill://star-wars-research/SKILL.md"), false, ALL, null));
        s.add(new Seed("resources/subscribe", "resources/subscribe",
                new JsonObject().put("uri", "swapi://people/1"), false, SESSIONS, null));
        s.add(new Seed("resources/unsubscribe", "resources/unsubscribe",
                new JsonObject().put("uri", "swapi://people/1"), false, SESSIONS, null));
        s.add(new Seed("notifications/cancelled", "notifications/cancelled",
                new JsonObject().put("requestId", 99).put("reason", "no longer needed"), true, ALL, null));
        s.add(new Seed("notifications/progress", "notifications/progress", new JsonObject()
                .put("progressToken", "p-1").put("progress", 1).put("total", 2).put("message", "half"), true,
                ALL, null));
        s.add(new Seed("notifications/roots/list_changed", "notifications/roots/list_changed", new JsonObject(),
                true, SESSIONS, null));
        return s;
    }

    /** The whole body of a seed in one era, with the _meta that era carries. */
    static JsonObject body(Seed seed, Era era) {
        JsonObject params = seed.params().copy();
        JsonObject meta = new JsonObject();
        if (!seed.notification()) {
            meta.put("progressToken", "p-" + ID);
        }
        switch (era) {
            case AUTO_INIT -> {
                // What auto-init reads to describe the client of the
                // session it creates (autoInitialRequest).
                if (!"initialize".equals(seed.method())) {
                    meta.put(McpRequestShape.CLIENT_INFO, implementation());
                    meta.put(McpRequestShape.CLIENT_CAPABILITIES, capabilities());
                }
            }
            case SESSION -> {
            }
            case STATELESS -> {
                meta.put(McpRequestShape.PROTOCOL_VERSION, STATELESS);
                meta.put(McpRequestShape.CLIENT_CAPABILITIES, capabilities());
                meta.put(McpRequestShape.CLIENT_INFO, implementation());
                meta.put("io.modelcontextprotocol/logLevel", "info");
            }
        }
        JsonObject withMeta = new JsonObject();
        if (!meta.isEmpty()) {
            withMeta.put("_meta", meta);
        }
        withMeta.mergeIn(params);
        JsonObject body = new JsonObject().put("jsonrpc", "2.0");
        if (!seed.notification()) {
            body.put("id", ID);
        }
        return body.put("method", seed.method()).put("params", withMeta);
    }

    // ------------------------------------------------------------- mutations

    private static final Object REMOVED = new Object();

    record Mutation(String kind, Object value) {
    }

    static List<Mutation> replacements() {
        return List.of(
                new Mutation("removed", REMOVED),
                new Mutation("null", null),
                new Mutation("string", "x"),
                new Mutation("number", 1),
                new Mutation("boolean", true),
                new Mutation("empty array", new JsonArray()),
                new Mutation("array", new JsonArray().add("x")),
                new Mutation("empty object", new JsonObject()));
    }

    /** Every member and element, at every depth, as a path of keys and indices. */
    static void paths(Object node, List<Object> path, List<List<Object>> out) {
        if (node instanceof JsonObject o) {
            for (String key : new ArrayList<>(o.fieldNames())) {
                List<Object> p = new ArrayList<>(path);
                p.add(key);
                out.add(p);
                paths(o.getValue(key), p, out);
            }
        } else if (node instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) {
                List<Object> p = new ArrayList<>(path);
                p.add(i);
                out.add(p);
                paths(a.getValue(i), p, out);
            }
        }
    }

    static String pointer(List<Object> path) {
        StringBuilder sb = new StringBuilder();
        for (Object step : path) {
            sb.append('/').append(step.toString().replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    static Object at(Object root, List<Object> path) {
        Object node = root;
        for (Object step : path) {
            node = step instanceof Integer i ? ((JsonArray) node).getValue(i) : ((JsonObject) node).getValue((String) step);
        }
        return node;
    }

    static Object copyOf(Object value) {
        if (value instanceof JsonObject o) {
            return o.copy();
        }
        if (value instanceof JsonArray a) {
            return a.copy();
        }
        return value;
    }

    /** The body with the member at path mutated; the path is not empty. */
    static String mutate(JsonObject seed, List<Object> path, Mutation mutation) {
        JsonObject root = seed.copy();
        Object parent = at(root, path.subList(0, path.size() - 1));
        Object last = path.get(path.size() - 1);
        Object value = copyOf(mutation.value());
        if (parent instanceof JsonObject o) {
            if (value == REMOVED) {
                o.remove((String) last);
            } else {
                o.put((String) last, value);
            }
        } else {
            JsonArray a = (JsonArray) parent;
            if (value == REMOVED) {
                a.remove((int) (Integer) last);
            } else {
                a.set((Integer) last, value);
            }
        }
        return root.encode();
    }

    static String wholeBody(Mutation mutation) {
        return mutation.value() == REMOVED ? "" : Json.encode(mutation.value());
    }

    record Case(String seed, Era era, String pointer, String kind, Map<String, String> headers, String body,
                boolean initialize) {
        String where() {
            return seed + " [" + era + "] " + (pointer.isEmpty() ? "(whole body)" : pointer) + " " + kind;
        }
    }

    static Map<String, String> headers(Seed seed, Era era, JsonObject body) {
        Map<String, String> h = new LinkedHashMap<>();
        switch (era) {
            case AUTO_INIT -> {
            }
            case SESSION -> h.put("MCP-Protocol-Version", SESSION_ERA);
            case STATELESS -> {
                h.put("MCP-Protocol-Version", STATELESS);
                h.put("Mcp-Method", seed.method());
                if (seed.nameMember() != null) {
                    h.put("Mcp-Name", body.getJsonObject("params").getString(seed.nameMember()));
                }
            }
        }
        return h;
    }

    static List<Case> cases(List<Seed> seeds) {
        List<Case> cases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Seed seed : seeds) {
            for (Era era : seed.eras()) {
                JsonObject body = body(seed, era);
                Map<String, String> headers = headers(seed, era, body);
                boolean initialize = "initialize".equals(seed.method());
                for (Mutation m : replacements()) {
                    add(cases, seen, new Case(seed.name(), era, "", m.kind(), headers, wholeBody(m), initialize));
                }
                List<List<Object>> paths = new ArrayList<>();
                paths(body, new ArrayList<>(), paths);
                for (List<Object> path : paths) {
                    Object original = at(body, path);
                    for (Mutation m : replacements()) {
                        if (m.value() != REMOVED && sameTypeAndValue(original, m.value())) {
                            continue;
                        }
                        add(cases, seen, new Case(seed.name(), era, pointer(path), m.kind(), headers,
                                mutate(body, path, m), initialize));
                    }
                }
            }
        }
        return cases;
    }

    private static void add(List<Case> cases, Set<String> seen, Case c) {
        // The same bytes with the same headers in the same era are one request.
        if (seen.add(c.era() + " " + c.headers() + " " + c.body())) {
            cases.add(c);
        }
    }

    static boolean sameTypeAndValue(Object original, Object replacement) {
        if (original == null || replacement == null) {
            return original == replacement;
        }
        if (original instanceof Number a && replacement instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return original.getClass() == replacement.getClass() && Objects.equals(original, replacement);
    }

    // ------------------------------------------------------------------ HTTP

    private HttpRequest request(Era era, Map<String, String> headers, String body, String session) {
        HttpRequest.Builder b = HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        if (era == Era.SESSION) {
            b.header("Mcp-Session-Id", session);
        }
        return b.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    private String openSession() throws Exception {
        HttpResponse<String> init = send(HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("jsonrpc", "2.0").put("id", 1)
                        .put("method", "initialize").put("params", new JsonObject()
                                .put("protocolVersion", SESSION_ERA).put("capabilities", capabilities())
                                .put("clientInfo", implementation())).encode()))
                .build());
        if (init == null || init.statusCode() != 200) {
            throw new AssertionError("could not open a session: " + (init == null ? "no answer" : init.body()));
        }
        return init.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private void close(String session) throws Exception {
        send(HttpRequest.newBuilder(mcp).header("Mcp-Session-Id", session).DELETE().build());
    }

    private Set<String> sessionIds() {
        Set<String> ids = new HashSet<>();
        sessions.forEach(c -> ids.add(c.id()));
        return ids;
    }

    /** The JSON-RPC message of a body, plain or as the last event of a stream; null if there is none. */
    static JsonObject message(HttpResponse<String> r) {
        String body = r.body();
        if (r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            String data = null;
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) {
                    data = line.substring(5).trim();
                }
            }
            body = data;
        }
        if (body == null) {
            return null;
        }
        try {
            return new JsonObject(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    enum Outcome { RESULT, ERROR, EMPTY }

    record Answer(Outcome outcome, String problem) {
    }

    static Answer judge(HttpResponse<String> r) {
        if (r == null) {
            return new Answer(null, "no answer within " + BUDGET_MS + " ms");
        }
        String seen = "HTTP " + r.statusCode() + " " + r.body().substring(0, Math.min(200, r.body().length()));
        if (r.statusCode() >= 500) {
            return new Answer(null, seen);
        }
        if (r.body().isEmpty()) {
            return r.statusCode() == 202 || r.statusCode() == 204
                    ? new Answer(Outcome.EMPTY, null)
                    : new Answer(null, "an empty body with " + seen);
        }
        JsonObject m = message(r);
        if (m == null || !"2.0".equals(m.getValue("jsonrpc"))) {
            return new Answer(null, "not a JSON-RPC message: " + seen);
        }
        boolean result = m.containsKey("result");
        boolean error = m.getValue("error") instanceof JsonObject;
        if (result == error) {
            return new Answer(null, "neither a result nor an error: " + seen);
        }
        if (result) {
            return new Answer(Outcome.RESULT, null);
        }
        JsonObject e = m.getJsonObject("error");
        if (!(e.getValue("code") instanceof Integer code)) {
            return new Answer(null, "an error without a code: " + seen);
        }
        if (code == -32603 && String.valueOf(e.getValue("message")).contains(DEADLINE_MESSAGE)) {
            return new Answer(null, "the filter's deadline answered: " + seen);
        }
        return new Answer(Outcome.ERROR, null);
    }

    // ------------------------------------------------------------------ test

    @Test
    void everyMutationOfAValidRequestIsAnsweredAtOnceAndLeavesNoState() throws Exception {
        List<Seed> seeds = seeds();
        Set<?> ongoing = ongoingRequests(extensionHandler);
        String session = openSession();
        int sessionsOpened = 1;

        // The seeds themselves are served: otherwise every mutation of a
        // broken seed would be a refusal, and the test would test nothing.
        List<String> broken = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        records.clear();
        for (Seed seed : seeds) {
            for (Era era : seed.eras()) {
                JsonObject body = body(seed, era);
                int logBefore = records.size();
                HttpResponse<String> r = send(request(era, headers(seed, era, body), body.encode(), session));
                Answer a = judge(r);
                Outcome wanted = seed.notification() ? Outcome.EMPTY : Outcome.RESULT;
                if (a.outcome() != wanted) {
                    broken.add(seed.name() + " [" + era + "]: " + (a.problem() != null ? a.problem()
                            : r.statusCode() + " " + r.body()));
                }
                // 2.17.2 (#126): a valid request logs nothing above INFO,
                // not even a line of the filter (reported with the mutations).
                for (LogRecord rec : records.subList(logBefore, records.size())) {
                    if (rec.getLevel().intValue() >= Level.WARNING.intValue() || rec.getThrown() != null) {
                        failures.add("seed " + seed.name() + " [" + era + "]: " + logged(rec));
                    }
                }
                if ("initialize".equals(seed.method()) && r != null) {
                    r.headers().firstValue("Mcp-Session-Id").ifPresent(id -> {
                        try {
                            close(id);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        }
        assertTrue(broken.isEmpty(), "seeds that are not served:\n" + String.join("\n", broken));

        List<Case> cases = cases(seeds);
        Map<Outcome, Integer> outcomes = new LinkedHashMap<>();
        Set<String> eras = new LinkedHashSet<>();
        records.clear();
        routeFailures.clear();
        long start = System.nanoTime();
        for (Case c : cases) {
            eras.add(c.era().name());
            if (c.era() == Era.SESSION && !sessions.has(session)) {
                // A mutation may end the session (the extension drops it
                // on some refusals); the next ones need a genuine one.
                session = openSession();
                sessionsOpened++;
            }
            Set<String> idsBefore = sessionIds();
            int ongoingBefore = ongoing.size();
            int logBefore = records.size();
            int routeFailuresBefore = routeFailures.size();

            HttpResponse<String> r = send(request(c.era(), c.headers(), c.body(), session));
            Answer a = judge(r);
            if (a.problem() != null) {
                failures.add(c.where() + ": " + a.problem());
            } else {
                outcomes.merge(a.outcome(), 1, Integer::sum);
            }
            // A session opened by an initialize that succeeded belongs to the
            // client: closed as a client closes it, then nothing may be left.
            if (c.initialize() && r != null && a.outcome() == Outcome.RESULT) {
                String opened = r.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (opened == null) {
                    failures.add(c.where() + ": an initialize served without Mcp-Session-Id");
                } else {
                    close(opened);
                }
            }
            boolean clean = settles(() -> ongoing.size() == ongoingBefore &&added(idsBefore).isEmpty());
            if (!clean) {
                failures.add(c.where() + ": state left behind, ongoing " + ongoingBefore + " -> " + ongoing.size()
                        + ", sessions added " + added(idsBefore).size());
                // Do not let one leak hide or shift the next findings.
                added(idsBefore).forEach(sessions::remove);
            }
            for (String f : routeFailures.subList(routeFailuresBefore, routeFailures.size())) {
                failures.add(c.where() + ": the request failed in the router (" + f + ")");
            }
            // 2.17.2 (#126): no malformed request makes the server log a line
            // per request. Only McpTransportFilter may log a WARN, which it
            // does once per kind per minute, without a stack trace; any
            // other WARN or above (the extension, Quarkus, Vert.x) fails.
            for (LogRecord rec : records.subList(logBefore, records.size())) {
                if (rec.getLevel().intValue() >= Level.SEVERE.intValue() || rec.getThrown() != null
                        || (rec.getLevel().intValue() >= Level.WARNING.intValue() && !filterLine(rec))) {
                    failures.add(c.where() + ": " + logged(rec));
                }
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        close(session);
        // The filter's net, whatever request it was for: its deadline (which
        // would fire after the budget above) or its failure handler.
        for (LogRecord rec : records) {
            String line = McpMalformedRequestTest.formatted(rec);
            if (McpTransportFilter.class.getName().equals(rec.getLoggerName()) && line != null
                    && (line.contains("no answer within") || line.contains("failed in the extension"))) {
                failures.add("the filter's net caught a request: " + line);
            }
        }
        System.out.printf("McpRequestMutationTest: %d seeds, eras %s, %d mutated requests in %d ms, %s,"
                        + " %d sessions opened for the session era, %d failures%n",
                seeds.size(), eras, cases.size(), ms, outcomes, sessionsOpened, failures.size());
        assertTrue(failures.isEmpty(), failures.size() + " mutated requests failed:\n"
                + String.join("\n", failures));
    }

    private static boolean filterLine(LogRecord rec) {
        return McpTransportFilter.class.getName().equals(rec.getLoggerName());
    }

    // The level, the logger and the message; the message may carry text of the
    // request, which is fine in a test report.
    private static String logged(LogRecord rec) {
        return "logged " + rec.getLevel() + " " + rec.getLoggerName() + ": " + McpMalformedRequestTest.formatted(rec)
                + (rec.getThrown() == null ? "" : " " + rec.getThrown());
    }

    private Set<String> added(Set<String> before) {
        Set<String> now = sessionIds();
        now.removeAll(before);
        return now;
    }

    // The extension removes an auto-init session and the entry of a request
    // in progress just after it answers: poll briefly, not a fixed sleep.
    private static boolean settles(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STATE_SETTLE_MS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }
}
