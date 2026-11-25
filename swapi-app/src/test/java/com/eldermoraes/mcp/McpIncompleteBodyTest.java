package com.eldermoraes.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.eldermoraes.mcp.McpMalformedRequestTest.sendWithDeadline;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.17.2 (#126): a POST to /mcp whose body never arrives whole. The body
 * handler that McpTransportFilter runs on /mcp (the only route with one)
 * fails the request when the connection closes mid-body or a chunk is
 * malformed (BodyHandlerImpl: context.fail(200, the exception), before the
 * request has ended). That failure reaches the filter's failure handler,
 * which passed it on, and Quarkus's error handler logged an ERROR for it at
 * every request (with the stack trace of the codec for a malformed chunk).
 * The failure handler now ends it there: the connection is closed, nothing
 * is written (the client got nothing before either), and the filter logs a
 * fixed line once per minute.
 *
 * Over a plain socket, three times each: a body shorter than its
 * Content-Length, a chunked body cut in the middle (the client closes its
 * side), a chunk size that is not a number and a chunk without its CRLF (the
 * server closes). Then the server serves the next request, and what the
 * failure handler must leave alone is still there: the 413 of a body over
 * the limit and the 400 of an Accept without both types, without that line.
 */
@QuarkusTest
class McpIncompleteBodyTest {

    static final String LINE = "An MCP request body did not arrive whole";
    private static final String ERROR_HANDLER = "io.quarkus.vertx.http.runtime.QuarkusErrorHandler";

    @TestHTTPResource("/mcp")
    URI mcp;

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

    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}";

    private static String head(String framing) {
        return "POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n" + framing + "\r\n";
    }

    record Variant(String name, String bytes, boolean clientCloses) {
    }

    static List<Variant> variants() {
        return List.of(
                new Variant("a body shorter than its Content-Length",
                        head("Content-Length: " + (BODY.length() + 40) + "\r\n") + BODY, true),
                new Variant("a chunked body cut in the middle",
                        head("Transfer-Encoding: chunked\r\n") + Integer.toHexString(BODY.length()) + "\r\n"
                                + BODY.substring(0, 10), true),
                new Variant("a chunk size that is not a number",
                        head("Transfer-Encoding: chunked\r\n") + "zz\r\n" + BODY + "\r\n0\r\n\r\n", false),
                new Variant("a chunk without its CRLF",
                        head("Transfer-Encoding: chunked\r\n") + Integer.toHexString(BODY.length()) + "\r\n"
                                + BODY + "XX\r\n0\r\n\r\n", false));
    }

    @Test
    void aBodyThatNeverArrivesWholeGetsNoAnswerAndOneLineOfTheFilter() throws Exception {
        List<String> failures = new ArrayList<>();
        records.clear();
        Map<String, Integer> filterLines = new LinkedHashMap<>();
        for (Variant v : variants()) {
            for (int i = 0; i < 3; i++) {
                int from = records.size();
                RawHttp.Exchange e = RawHttp.exchange(mcp.getPort(), RawHttp.ascii(v.bytes()), v.clientCloses(), 3000);
                if (e.bytes().length != 0) {
                    failures.add(v.name() + ": the client got " + e.bytes().length + " bytes: " + e.head());
                }
                if (!e.closedByServer()) {
                    failures.add(v.name() + ": the connection was not closed");
                }
                // The failure is handled on the event loop after the close.
                Within.within(500, () -> false);
                for (LogRecord rec : records.subList(from, records.size())) {
                    boolean filter = McpTransportFilter.class.getName().equals(rec.getLoggerName());
                    if (filter && String.valueOf(McpMalformedRequestTest.formatted(rec)).startsWith(LINE)
                            && rec.getThrown() == null) {
                        filterLines.merge(LINE, 1, Integer::sum);
                    } else if (rec.getLevel().intValue() >= Level.WARNING.intValue()
                            || ERROR_HANDLER.equals(rec.getLoggerName())) {
                        failures.add(v.name() + ": logged " + McpExtensionLogGuardTest.describe(rec));
                    }
                }
            }
            HttpResponse<String> next = sendWithDeadline(HttpRequest.newBuilder(mcp)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(BODY)).build(), 5000);
            if (next == null || next.statusCode() != 200 || !next.body().contains("sw_get")) {
                failures.add(v.name() + ": the next request was not served: "
                        + (next == null ? "no answer" : next.statusCode() + " " + next.body()));
            }
        }
        // At most one: the kind may have gone out earlier in this minute.
        filterLines.forEach((line, n) -> {
            if (n > 1) {
                failures.add(n + " lines \"" + line + "\" within a minute");
            }
        });
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // What the failure handler leaves alone: failures without an exception.
    @Test
    void the413AndTheAcceptRefusalStayAsTheyWere() throws Exception {
        records.clear();
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"pad\":\""
                        + "y".repeat(256 * 1024) + "\"}}")
        .when()
                .post(mcp)
        .then()
                .statusCode(413);
        HttpResponse<String> accept = sendWithDeadline(HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build(), 5000);
        assertNotNull(accept, "no answer");
        assertEquals(400, accept.statusCode(), accept.body());
        assertEquals("", accept.body());
        Within.within(300, () -> false);
        for (LogRecord rec : records) {
            String text = String.valueOf(McpMalformedRequestTest.formatted(rec));
            assertTrue(!text.startsWith(LINE), "the 413 or the 400 counted as a body cut: " + text);
            assertTrue(!ERROR_HANDLER.equals(rec.getLoggerName()) || rec.getLevel().intValue() < Level.WARNING.intValue(),
                    McpExtensionLogGuardTest.describe(rec));
        }
    }

    // A form body that the body handler's decoder refuses arrived whole, but
    // the handler fails it before the request ends: with 400 when the
    // decoder fails as the data comes (a DecoderException), through the
    // filter's answer to an exception (-32603) when it fails at the end.
    // Neither is a body cut short, and neither answer changes.
    @Test
    void aFormBodyTheDecoderRefusesKeepsItsAnswer() throws Exception {
        records.clear();
        HttpResponse<String> during = sendWithDeadline(HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("a=%zz&b=%")).build(), 5000);
        assertNotNull(during, "no answer");
        assertEquals(400, during.statusCode(), during.body());
        HttpResponse<String> atTheEnd = sendWithDeadline(HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("a=%zz")).build(), 5000);
        assertNotNull(atTheEnd, "no answer");
        assertEquals(200, atTheEnd.statusCode(), atTheEnd.body());
        assertEquals(-32603, new io.vertx.core.json.JsonObject(atTheEnd.body()).getJsonObject("error")
                .getInteger("code"), atTheEnd.body());
        Within.within(300, () -> false);
        for (LogRecord rec : records) {
            assertTrue(!String.valueOf(McpMalformedRequestTest.formatted(rec)).startsWith(LINE),
                    "a form body counted as a body cut: " + McpExtensionLogGuardTest.describe(rec));
        }
    }
}
