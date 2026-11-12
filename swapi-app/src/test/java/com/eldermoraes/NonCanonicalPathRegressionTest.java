package com.eldermoraes;

import com.eldermoraes.stats.RouteNormalizer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #105: an address under /api has one spelling. #55 refused the aliases
 * of the id; the rest of the path had its own: Vert.x collapses repeated
 * slashes, removes dot segments and decodes percent-encoded letters before
 * RESTEasy Reactive matches, and RESTEasy Reactive drops matrix parameters.
 * Each of those answered 200 with the body of the canonical address. They are
 * the 404 of the contract now, as on the pages.
 *
 * Requests go over a raw socket: REST Assured collapses "//" before sending,
 * so a doubled slash written in a REST Assured test never reaches the server.
 */
@QuarkusTest
class NonCanonicalPathRegressionTest {

    private static final String EDGE_CACHE =
            "public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400";

    /** The canonical addresses, each written with the aliases this issue refuses. */
    @ParameterizedTest
    @ValueSource(strings = {
            // record
            "/api//people/1", "/api/people//1", "//api/people/1", "/api/people/1//",
            "/api/./people/1", "/api/people/./1", "/api/films/../people/1",
            "/api/%70eople/1", "/%61pi/people/1", "/api/people;x=1/1",
            // list
            "/api//people", "/api/%70eople", "/api/./people", "/api/people//", "/api/people;x=1",
            // random
            "/api//people/random", "/api/%70eople/random", "/api/people/%72andom",
            // stats and the root
            "/api//stats", "/api/%73tats", "/api/stats;x=1", "//api", "/api//", "/api/."})
    void pathAliasIsTheContract404(String alias) throws IOException {
        Answer answer = send("GET", alias);
        assertEquals(404, answer.status, alias);
        assertTrue(answer.header("content-type").startsWith("text/plain"), alias + ": " + answer.header("content-type"));
        assertEquals("No resource found at " + alias, answer.body, alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/films//1", "/api/planets/./1", "/api/%73pecies/1",
            "/api/starships;x=1/2", "/api//vehicles/4", "/api//films"})
    void theRuleHoldsForEveryType(String alias) throws IOException {
        Answer answer = send("GET", alias);
        assertEquals(404, answer.status, alias);
        assertEquals("No resource found at " + alias, answer.body, alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/people/1", "/api/people", "/api/people/random", "/api/stats", "/api",
            "/api/films/1", "/api/starships/2", "/api/vehicles/4", "/api/people?search=luke"})
    void canonicalAddressStillAnswers200(String path) throws IOException {
        Answer answer = send("GET", path);
        assertEquals(200, answer.status, path);
        assertTrue(answer.header("content-type").startsWith("application/json"), path);
    }

    // Decision of #105: one trailing slash is the spelling of swapi.dev, whose
    // clients send it; it stays 200 (bounded: one more spelling per address).
    @ParameterizedTest
    @ValueSource(strings = {"/api/people/1/", "/api/people/", "/api/people/random/", "/api/stats/", "/api/"})
    void oneTrailingSlashStillAnswers200(String path) throws IOException {
        assertEquals(200, send("GET", path).status, path);
    }

    @Test
    void recordBodyIsTheSameWithOneTrailingSlash() throws IOException {
        String bare = given().get("/api/people/1").then().statusCode(200).extract().asString();
        assertEquals(bare, send("GET", "/api/people/1/").body);
    }

    // These never reached a resource; they stay 404.
    @ParameterizedTest
    @ValueSource(strings = {"/api/People/1", "/API/people/1", "/api;x=1/people/1"})
    void caseAndAMatrixOnTheRootStay404(String path) throws IOException {
        assertEquals(404, send("GET", path).status, path);
    }

    @Test
    void pathAlias404IsCacheableLikeAnyOther404() throws IOException {
        Answer answer = send("GET", "/api//people/1");
        assertEquals(404, answer.status);
        assertEquals(EDGE_CACHE, answer.header("cache-control"));
        assertEquals("nosniff", answer.header("x-content-type-options"));
    }

    @Test
    void headOfAPathAliasIs404() throws IOException {
        assertEquals(404, send("HEAD", "/api/%70eople/1").status);
    }

    // An alias of the id keeps the answer of #55, on the wire this time.
    @Test
    void idAliasAfterADoubledSlashKeepsTheIdMessage() throws IOException {
        Answer answer = send("GET", "/api//people/01");
        assertEquals(404, answer.status);
        assertEquals("No people found with id 01", answer.body);
    }

    // A dot segment after the id leaves no id as the last segment: #55 refuses
    // it first, with its own message. Pinned on the wire, where it is sent.
    @ParameterizedTest
    @ValueSource(strings = {"/api/people/1/.", "/api/people/1/x/.."})
    void dotSegmentAfterTheIdIs404(String alias) throws IOException {
        Answer answer = send("GET", alias);
        assertEquals(404, answer.status, alias);
        assertTrue(answer.header("content-type").startsWith("text/plain"), alias);
    }

    @Test
    void countersKeepOnlyTemplatesTheNormalizerProduces() throws IOException {
        for (String alias : new String[] {"/api//people/1", "/api/%70eople/1", "/api/people;x=1/1",
                "/api/./people", "//api", "/api/%73tats"}) {
            send("GET", alias);
        }
        Map<String, Object> routes = given().get("/api/stats").then().statusCode(200)
                .extract().jsonPath().getMap("routes");
        assertTrue(routes.containsKey("/api/people/{id}"), routes.keySet().toString());
        for (String template : routes.keySet()) {
            assertEquals(Optional.of(template), RouteNormalizer.normalize(template), template);
        }
    }

    // --- issue #107: a refused alias is counted like any other 404 ---

    /**
     * A refused alias is counted under the template of the path it spells, as
     * the ordinary 404 of the same address is: the counters count requests
     * that reached a route, whatever the answer.
     */
    @ParameterizedTest
    @CsvSource({
            // the ordinary 404 the others are compared with
            "/api/people/9999, /api/people/{id}",
            // #105: the path before the id
            "/api//people/1, /api/people/{id}",
            "//api/people/1, /api/people/{id}",
            "/api/people/1//, /api/people/{id}",
            "/api/./people/1, /api/people/{id}",
            "/api/films/../people/1, /api/people/{id}",
            "/api/%70eople/1, /api/people/{id}",
            "/%61pi/people/1, /api/people/{id}",
            // #55: the id
            "/api/people/01, /api/people/{id}",
            "/api/people/%31, /api/people/{id}",
            "/api/people/1;x=1, /api/people/{id}",
            // a list, /random and the root
            "/api//people, /api/people",
            "/api//people/random, /api/people/random",
            "//api, /api",
            // a type segment no route has: other, like an unknown path
            "/api/people;x=1/1, other",
            "/api/People/1, other",
            "/api/zz-unknown, other"})
    void refusedAliasIsCountedLikeAnyOther404(String alias, String template) throws IOException {
        long before = routeCount(template);
        assertEquals(404, send("GET", alias).status, alias);
        assertEquals(before + 1, routeCount(template), alias + " under " + template);
    }

    /** /api/stats counts the read of the counters too, so its alias is checked on its own. */
    @Test
    void aliasOfTheStatsIsCountedUnderTheStats() throws IOException {
        long before = routeCount("/api/stats");
        assertEquals(404, send("GET", "/api//stats").status);
        // the alias, then the read of the counters itself
        assertEquals(before + 2, routeCount("/api/stats"));
    }

    /** A route is timed exactly when it is counted: refused aliases included. */
    @Test
    void refusedAliasIsTimedUnderTheTemplateItIsCountedUnder() throws IOException, InterruptedException {
        String[] aliases = {"/api//people/1", "//api/people/1", "/api/people/1//", "/api/./people/1",
                "/api/films/../people/1", "/api/%70eople/1", "/%61pi/people/1",
                "/api/people/01", "/api/people/%31", "/api/people/1;x=1"};
        endEarlierRequests();
        long counted = routeCount("/api/people/{id}");
        long timed = timed("/api/people/{id}");
        for (String alias : aliases) {
            assertEquals(404, send("GET", alias).status, alias);
        }
        endEarlierRequests();
        assertEquals(counted + aliases.length, routeCount("/api/people/{id}"));
        assertEquals(timed + aliases.length, timed("/api/people/{id}"));
    }

    /**
     * Sends one timed request and waits until its time shows: a time is recorded
     * once the response has ended, so every request sent before it has ended too.
     */
    private static void endEarlierRequests() throws InterruptedException {
        long films = timed("/api/films");
        given().get("/api/films").then().statusCode(200);
        assertTrue(awaitTimed("/api/films", films + 1) >= films + 1);
    }

    private static long routeCount(String route) {
        Number n = given().get("/api/stats").then().statusCode(200).extract().path("routes.'" + route + "'");
        return n == null ? 0 : n.longValue();
    }

    /** Timed requests of a route in the last 24 hours (the time is recorded once the response has ended). */
    private static long timed(String route) {
        Number n = given().get("/api/stats?period=24h").then().statusCode(200)
                .extract().path("performance.routes.'" + route + "'.count");
        return n == null ? 0 : n.longValue();
    }

    private static long awaitTimed(String route, long atLeast) throws InterruptedException {
        long n = timed(route);
        for (int i = 0; i < 100 && n < atLeast; i++) {
            Thread.sleep(20);
            n = timed(route);
        }
        return n;
    }

    // --- a minimal HTTP/1.1 client that sends the path exactly as written ---

    private record Answer(int status, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.getOrDefault(name, "");
        }
    }

    private static Answer send(String method, String path) throws IOException {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost:" + RestAssured.port + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    private static Answer parse(byte[] raw) {
        int split = indexOf(raw, "\r\n\r\n", 0);
        String[] head = new String(raw, 0, split, StandardCharsets.US_ASCII).split("\r\n");
        int status = Integer.parseInt(head[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < head.length; i++) {
            int colon = head[i].indexOf(':');
            headers.put(head[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), head[i].substring(colon + 1).trim());
        }
        int pos = split + 4;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            while (true) {
                int lineEnd = indexOf(raw, "\r\n", pos);
                String sizeLine = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII);
                int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                body.write(raw, lineEnd + 2, size);
                pos = lineEnd + 2 + size + 2;
            }
        } else {
            body.write(raw, pos, raw.length - pos);
        }
        return new Answer(status, headers, body.toString(StandardCharsets.UTF_8));
    }

    private static int indexOf(byte[] data, String ascii, int from) {
        byte[] needle = ascii.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = from; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException("no " + ascii.replace("\r\n", "CRLF") + " in the response");
    }
}
