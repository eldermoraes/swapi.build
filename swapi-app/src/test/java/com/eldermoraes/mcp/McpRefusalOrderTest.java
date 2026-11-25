package com.eldermoraes.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.eldermoraes.mcp.McpMalformedRequestTest.STATELESS;
import static com.eldermoraes.mcp.McpMalformedRequestTest.sendWithDeadline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.17.2 (#126): a request with two defects, one of them a refusal the
 * filter now writes in the extension's place, gets the answer of the defect
 * the extension (or the filter) checks first. The cases that can be compared
 * with the extension itself are in McpExtensionLogGuardTest (the Accept
 * before the body, the versions, the stateless headers, the log level of
 * _meta, before the cursor and the cancel); these are the ones where the
 * filter answers first, on purpose, and the extension would not answer the
 * same (a batch, a shape the extension cannot handle). The listen keeps its
 * answer whatever the body (McpListenLimitTest).
 */
@QuarkusTest
class McpRefusalOrderTest {

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

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(mcp)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder stateless(String method, String body) {
        return post(body).header("MCP-Protocol-Version", STATELESS).header("Mcp-Method", method);
    }

    private static JsonObject error(HttpResponse<String> r, int status, int code) {
        assertNotNull(r, "no answer");
        assertEquals(status, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), r.body());
        JsonObject error = new JsonObject(r.body()).getJsonObject("error");
        assertEquals(code, error.getInteger("code"), r.body());
        return error;
    }

    @Test
    void aCancelInABatchGetsTheBatchAnswer() throws Exception {
        HttpResponse<String> r = sendWithDeadline(post("""
                [{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}]""").build(), 5000);
        assertTrue(error(r, 400, -32600).getString("message").contains("batch"), r.body());
    }

    @Test
    void aCancelWithAShapeTheExtensionCannotHandleKeepsTheShapeRefusal() throws Exception {
        HttpResponse<String> params = sendWithDeadline(post("""
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":"x"}""").build(), 5000);
        assertTrue(error(params, 200, -32602).getString("message").contains("params must be an object"),
                params.body());
        HttpResponse<String> id = sendWithDeadline(post("""
                {"jsonrpc":"2.0","id":{},"method":"notifications/cancelled","params":{"requestId":1}}""").build(),
                5000);
        assertTrue(error(id, 200, -32600).getString("message").contains("id must be"), id.body());
    }

    // The log level of _meta comes after what buildStatelessInitialRequest
    // decodes: with a clientInfo the extension cannot decode (it fails with
    // HTTP 500) the filter's refusal stands, whatever the log level.
    @Test
    void aClientInfoTheExtensionCannotDecodeIsRefusedBeforeTheLogLevel() throws Exception {
        HttpResponse<String> r = sendWithDeadline(stateless("tools/list", """
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"cursor":"x","_meta":{
                  "io.modelcontextprotocol/protocolVersion":"%s","io.modelcontextprotocol/clientCapabilities":{},
                  "io.modelcontextprotocol/clientInfo":{},"io.modelcontextprotocol/logLevel":"bogus"}}}"""
                .formatted(STATELESS)).build(), 5000);
        assertTrue(error(r, 400, -32602).getString("message").contains("clientInfo.name"), r.body());
    }

    // Ten requests of each kind within a minute: at most one line of the
    // filter per kind (a kind may have gone out earlier in this minute), a
    // fixed label, no stack trace; the cancel logs nothing.
    @Test
    void eachKindLogsAtMostOneFixedLineAMinute() throws Exception {
        Map<String, HttpRequest> kinds = new java.util.LinkedHashMap<>();
        kinds.put("body", post("\"x\"").build());
        kinds.put("Accept", HttpRequest.newBuilder(mcp).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/list"}""")).build());
        kinds.put("cursor", post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"cursor":"x"}}""").build());
        kinds.put("cancel", post("""
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":99}}""").build());
        records.clear();
        for (HttpRequest request : kinds.values()) {
            for (int i = 0; i < 10; i++) {
                assertNotNull(sendWithDeadline(request, 5000), "no answer");
            }
        }
        List<LogRecord> warnings = records.stream()
                .filter(rec -> rec.getLevel().intValue() >= Level.WARNING.intValue()).toList();
        for (LogRecord rec : warnings) {
            assertEquals(McpTransportFilter.class.getName(), rec.getLoggerName(), McpExtensionLogGuardTest.describe(rec));
            assertEquals(null, rec.getThrown(), McpExtensionLogGuardTest.describe(rec));
        }
        Map<String, Long> lines = warnings.stream().collect(Collectors.groupingBy(
                rec -> String.valueOf(McpMalformedRequestTest.formatted(rec)), Collectors.counting()));
        List<String> labels = List.of(
                "Refused a malformed MCP request: body not a JSON object (at most one line of this kind per minute)",
                "Refused a malformed MCP request: Accept (at most one line of this kind per minute)",
                "Refused a malformed MCP request: cursor (at most one line of this kind per minute)");
        lines.forEach((line, count) -> {
            assertTrue(labels.contains(line), "not a fixed label: " + line);
            assertEquals(1L, count, line);
        });
    }
}
