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

    static final String FORM_LINE = "Refused a malformed MCP request: form body";
    // Text of the request that must never reach the log.
    private static final String MARK = "zqmark";
    private static final String URLENCODED = "application/x-www-form-urlencoded";
    private static final String MULTIPART = "multipart/form-data; boundary=xyz";

    record Form(String name, String contentType, String body) {
    }

    private static String part(String headers, String value) {
        return "--xyz\r\n" + headers + "\r\n\r\n" + value + "\r\n";
    }

    // The form bodies the body handler's decoder (Netty's HttpPostRequestDecoder,
    // fed by Vert.x as the data arrives) refuses before the request ends. The
    // body handler fails them with 400 (a DecoderException), with the
    // exception or its cause: the decoder's own exception, an IOException of
    // an attribute over quarkus.http.limits.max-form-attribute-size, a
    // NullPointerException, an UnsupportedCharsetException. Up to 2.17.1
    // Quarkus's error handler answered them with its error page and logged
    // most at every request, with a stack trace.
    static List<Form> refusedForms() {
        return List.of(
                new Form("a bad escape", URLENCODED, "a=%zz&" + MARK + "=%"),
                new Form("more fields than quarkus.http.limits.max-form-fields", URLENCODED,
                        String.join("&", java.util.stream.IntStream.range(0, 300).mapToObj(i -> MARK + i + "=1")
                                .toList())),
                new Form("a field over quarkus.http.limits.max-form-buffered-bytes", URLENCODED,
                        MARK + "=" + "x".repeat(3000) + "&b=1"),
                new Form("a multipart attribute over max-form-attribute-size", MULTIPART,
                        part("Content-Disposition: form-data; name=\"" + MARK + "\"", "x".repeat(3000)) + "--xyz--\r\n"),
                new Form("more multipart fields than max-form-fields", MULTIPART,
                        String.join("", java.util.stream.IntStream.range(0, 300).mapToObj(i ->
                                part("Content-Disposition: form-data; name=\"" + MARK + i + "\"", "1")).toList())
                                + "--xyz--\r\n"),
                new Form("a multipart part header over max-form-buffered-bytes", MULTIPART,
                        part("Content-Disposition: form-data; name=\"a\"\r\nX-" + MARK + ": " + "h".repeat(5000), "v")
                                + "--xyz--\r\n"),
                new Form("a multipart part without Content-Disposition", MULTIPART,
                        part("X-" + MARK + ": y", "v") + "--xyz--\r\n"),
                new Form("a multipart part with an unknown charset", MULTIPART,
                        part("Content-Disposition: form-data; name=\"a\"\r\nContent-Type: text/plain; charset="
                                + MARK, "v") + "--xyz--\r\n"));
    }

    private HttpResponse<String> form(String contentType, String body) throws Exception {
        return sendWithDeadline(HttpRequest.newBuilder(mcp)
                .header("Content-Type", contentType)
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), 5000);
    }

    // 2.17.2: answered by the filter's failure handler, with the status the
    // body handler gave (400) and no body (the error page carried a random
    // error id), logged once a minute with a fixed label.
    @Test
    void aFormBodyTheDecoderRefusesIs400WithoutABodyAndOneLine() throws Exception {
        List<String> failures = new ArrayList<>();
        records.clear();
        int formLines = 0;
        for (Form f : refusedForms()) {
            for (int i = 0; i < 3; i++) {
                int from = records.size();
                HttpResponse<String> r = form(f.contentType(), f.body());
                if (r == null || r.statusCode() != 400 || !r.body().isEmpty()) {
                    failures.add(f.name() + ": " + (r == null ? "no answer" : r.statusCode() + " " + r.body()));
                }
                Within.within(300, () -> false);
                for (LogRecord rec : records.subList(from, records.size())) {
                    String text = String.valueOf(McpMalformedRequestTest.formatted(rec));
                    boolean filter = McpTransportFilter.class.getName().equals(rec.getLoggerName());
                    if (filter && text.startsWith(FORM_LINE) && rec.getThrown() == null) {
                        formLines++;
                    } else if (rec.getLevel().intValue() >= Level.WARNING.intValue()
                            || ERROR_HANDLER.equals(rec.getLoggerName())) {
                        failures.add(f.name() + ": logged " + McpExtensionLogGuardTest.describe(rec));
                    }
                    if (text.contains(MARK) || text.contains("%zz")) {
                        failures.add(f.name() + ": the request's text in the log: "
                                + McpExtensionLogGuardTest.describe(rec));
                    }
                }
            }
            HttpResponse<String> next = form("application/json", BODY);
            if (next == null || next.statusCode() != 200 || !next.body().contains("sw_get")) {
                failures.add(f.name() + ": the next request was not served");
            }
        }
        if (formLines > 1) {
            failures.add(formLines + " lines \"" + FORM_LINE + "\" within a minute");
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // The form bodies that do not fail in the body handler before the end
    // keep their answers: a bad escape in the last field (Vert.x decodes it
    // once the request has ended, and the request goes on), an empty
    // boundary (the decoder refuses it when the body handler starts, a 500),
    // both answered -32603 by the failure handler and logged at most once a
    // minute as before; a form body that is JSON is served.
    @Test
    void theOtherFormBodiesKeepTheirAnswers() throws Exception {
        records.clear();
        for (String[] f : List.of(new String[]{URLENCODED, "a=%zz"},
                new String[]{"multipart/form-data; boundary=", "x"})) {
            for (int i = 0; i < 3; i++) {
                HttpResponse<String> r = form(f[0], f[1]);
                assertNotNull(r, "no answer");
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(-32603, new io.vertx.core.json.JsonObject(r.body()).getJsonObject("error")
                        .getInteger("code"), r.body());
            }
        }
        HttpResponse<String> served = form(URLENCODED, BODY);
        assertNotNull(served, "no answer");
        assertEquals(200, served.statusCode(), served.body());
        assertTrue(served.body().contains("sw_get"), served.body());
        Within.within(300, () -> false);
        Map<String, Integer> lines = new LinkedHashMap<>();
        for (LogRecord rec : records) {
            String text = String.valueOf(McpMalformedRequestTest.formatted(rec));
            assertTrue(!text.startsWith(LINE) && !text.startsWith(FORM_LINE), McpExtensionLogGuardTest.describe(rec));
            assertTrue(!ERROR_HANDLER.equals(rec.getLoggerName()) || rec.getLevel().intValue() < Level.WARNING.intValue(),
                    McpExtensionLogGuardTest.describe(rec));
            if (rec.getLevel().intValue() >= Level.WARNING.intValue()) {
                assertEquals(McpTransportFilter.class.getName(), rec.getLoggerName(),
                        McpExtensionLogGuardTest.describe(rec));
                lines.merge(text, 1, Integer::sum);
            }
        }
        lines.forEach((line, n) -> assertEquals(1, n, line));
    }
}
