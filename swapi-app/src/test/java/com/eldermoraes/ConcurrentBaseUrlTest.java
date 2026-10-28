package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class ConcurrentBaseUrlTest {

    // Concurrent requests with different hosts must not contaminate each
    // other's URLs — each response carries only the host of its own requester.
    @Test
    public void concurrentRequestsKeepTheirOwnHost() throws Exception {
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                final String host = (i % 2 == 0) ? "a.example" : "b.example";
                final String other = (i % 2 == 0) ? "b.example" : "a.example";
                results.add(pool.submit(() -> {
                    String body = given()
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", host)
                            .when().get("/api/people/1")
                            .then().statusCode(200)
                            .extract().asString();
                    return body.contains("https://" + host + "/api/people/1")
                            && !body.contains(other);
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get(30, TimeUnit.SECONDS),
                        "response contaminated with another request's host");
            }
        } finally {
            pool.shutdown();
        }
    }

    // 2.12.0: the record pages read the same entity getters on the event loop
    // (RecordViews), mixed with the REST API on worker threads. Each body, page
    // or JSON, carries only its own host, and the page never shows "null/".
    @Test
    public void recordPagesAndTheApiKeepTheirOwnHost() throws Exception {
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                final String host = (i % 2 == 0) ? "a.example" : "b.example";
                final String other = (i % 2 == 0) ? "b.example" : "a.example";
                final boolean page = (i / 2) % 2 == 0;
                results.add(pool.submit(() -> {
                    String body = given()
                            .accept(page ? "text/html" : "application/json")
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", host)
                            .when().get(page ? "/resource/people/1" : "/api/people/1")
                            .then().statusCode(200)
                            .extract().asString();
                    String want = page ? "&quot;https://" + host + "/api/people/1&quot;" : "https://" + host + "/api/people/1";
                    if (!body.contains(want) || body.contains(other) || body.contains("null/")) {
                        return (page ? "page" : "api") + " on " + host;
                    }
                    return "";
                }));
            }
            for (Future<String> f : results) {
                String wrong = f.get(30, TimeUnit.SECONDS);
                assertTrue(wrong.isEmpty(), "response contaminated: " + wrong);
            }
        } finally {
            pool.shutdown();
        }
    }

    private static final String STATELESS_META = """
            "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
              "io.modelcontextprotocol/clientInfo":{"name":"concurrent-test","version":"1.0"},
              "io.modelcontextprotocol/clientCapabilities":{}}""";

    // 2.13.0 (slice F1): MCP tools (with expand, which reads related records)
    // and resource reads set RequestBaseUrl through withBaseUrl on pooled
    // threads. 200 of each, hosts alternated: each body carries only its own
    // host, for the record and for the expanded homeworld.
    @Test
    public void concurrentMcpCallsKeepTheirOwnHost() throws Exception {
        int rounds = 400;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                final String host = (i % 2 == 0) ? "a.example" : "b.example";
                final String other = (i % 2 == 0) ? "b.example" : "a.example";
                final boolean tool = (i / 2) % 2 == 0;
                results.add(pool.submit(() -> {
                    String body = given()
                            .contentType("application/json")
                            .accept("application/json, text/event-stream")
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", host)
                            .header("MCP-Protocol-Version", "2026-07-28")
                            .header("Mcp-Method", tool ? "tools/call" : "resources/read")
                            .header("Mcp-Name", tool ? "sw_get" : "swapi://people/1")
                            .body(tool
                                    ? """
                                      {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sw_get",
                                       "arguments":{"resource":"PEOPLE","id":1,"expand":["homeworld"]},%s}}
                                      """.formatted(STATELESS_META)
                                    : """
                                      {"jsonrpc":"2.0","id":1,"method":"resources/read",
                                       "params":{"uri":"swapi://people/1",%s}}
                                      """.formatted(STATELESS_META))
                            .when().post("/mcp")
                            .then().statusCode(200)
                            .extract().asString();
                    boolean ok = body.contains("https://" + host + "/api/people/1")
                            && body.contains("https://" + host + "/api/planets/1")
                            && !body.contains(other)
                            && (!tool || body.contains("expanded"));
                    return ok ? "" : (tool ? "tools/call" : "resources/read") + " on " + host + ": "
                            + body.substring(0, Math.min(200, body.length()));
                }));
            }
            for (Future<String> f : results) {
                String wrong = f.get(60, TimeUnit.SECONDS);
                assertTrue(wrong.isEmpty(), "response contaminated: " + wrong);
            }
        } finally {
            pool.shutdown();
        }
    }
}
