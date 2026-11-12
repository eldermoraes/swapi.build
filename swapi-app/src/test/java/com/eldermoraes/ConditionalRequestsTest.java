package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * The validator of the pages, the Markdown copies and /llms-full.txt (#58): the
 * HTML must revalidate on every visit (max-age=0, must-revalidate), so it
 * carries a strong ETag and a conditional request with it answers 304 with no
 * body and the caching headers of the 200.
 */
@QuarkusTest
class ConditionalRequestsTest {

    private static final String HTML_CACHE_CONTROL = "public, max-age=0, must-revalidate, s-maxage=300";
    private static final String SEO_CACHE_CONTROL = "public, max-age=300, s-maxage=86400";

    private static RequestSpecification on(String host) {
        return given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", host)
                .urlEncodingEnabled(false).redirects().follow(false);
    }

    private static RequestSpecification preview() {
        return on("preview.example");
    }

    private static String tagOf(String path) {
        Response response = preview().when().get(path);
        Assertions.assertEquals(200, response.statusCode(), path);
        Assertions.assertEquals(1, response.headers().getValues("ETag").size(), path + " ETag headers");
        return response.header("ETag");
    }

    /** The tag the spec describes, computed here and not by the application. */
    private static String digestTag(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return '"' + Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16)) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cacheControlOf(String path) {
        return path.endsWith(".md") || path.endsWith(".txt") ? SEO_CACHE_CONTROL : HTML_CACHE_CONTROL;
    }

    // A fixed page drawn by the server, one the client draws, a list, a record of
    // each indexing, the copies (cached and per request) and the full file.
    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/docs/mcp", "/faq", "/about", "/privacy", "/stats", "/resource/planets",
            "/resource/people/1", "/resource/films/1", "/index.html.md", "/docs.md", "/docs/mcp.md",
            "/resource/films/1.md", "/resource/people/1.md", "/llms-full.txt"})
    void validatorIsTheStrongDigestOfTheBytesSent(String path) {
        Response response = preview().when().get(path);
        Assertions.assertEquals(200, response.statusCode(), path);
        Assertions.assertEquals(List.of(digestTag(response.asByteArray())), response.headers().getValues("ETag"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/docs/mcp", "/privacy", "/resource/planets", "/resource/people/1",
            "/index.html.md", "/docs.md", "/resource/films/1.md", "/resource/people/1.md", "/llms-full.txt"})
    void sameValidatorAnswers304WithoutABodyAndWithTheCachingHeaders(String path) {
        String tag = tagOf(path);
        for (Response response : List.of(
                preview().header("Origin", "https://app.example").header("If-None-Match", tag).when().get(path),
                preview().header("Origin", "https://app.example").header("If-None-Match", tag).when().head(path))) {
            Assertions.assertEquals(304, response.statusCode(), path);
            Assertions.assertEquals(0, response.asByteArray().length, path + " body");
            Assertions.assertEquals(List.of(tag), response.headers().getValues("ETag"), path);
            Assertions.assertEquals(List.of(cacheControlOf(path)), response.headers().getValues("Cache-Control"), path);
            Assertions.assertTrue(String.join(",", response.headers().getValues("Vary")).contains("Origin"),
                    path + " Vary: " + response.headers().getValues("Vary"));
            Assertions.assertNull(response.header("Content-Type"), path + " Content-Type");
            Assertions.assertNull(response.header("Content-Length"), path + " Content-Length");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/docs", "/resource/people/1", "/docs.md", "/resource/films/1.md", "/llms-full.txt"})
    void headCarriesTheValidatorOfGet(String path) {
        Response head = preview().when().head(path);
        Assertions.assertEquals(200, head.statusCode(), path);
        Assertions.assertEquals(List.of(tagOf(path)), head.headers().getValues("ETag"), path);
    }

    // The indexing of the 200 stays on the 304: a cache updates the stored
    // response from these headers.
    @Test
    void notModifiedKeepsTheLinkAndRobotsHeadersOfThe200() {
        Response page = preview().header("If-None-Match", tagOf("/docs")).when().get("/docs");
        Assertions.assertEquals(304, page.statusCode());
        Assertions.assertEquals(List.of("</docs.md>; rel=\"alternate\"; type=\"text/markdown\""),
                page.headers().getValues("Link"));

        Response copy = preview().header("If-None-Match", tagOf("/resource/people/1.md"))
                .when().get("/resource/people/1.md");
        Assertions.assertEquals(304, copy.statusCode());
        Assertions.assertEquals(List.of("<https://preview.example/resource/people>; rel=\"canonical\""),
                copy.headers().getValues("Link"));
        Assertions.assertEquals(List.of("noindex"), copy.headers().getValues("X-Robots-Tag"));

        Response full = preview().header("If-None-Match", tagOf("/llms-full.txt")).when().get("/llms-full.txt");
        Assertions.assertEquals(304, full.statusCode());
        Assertions.assertEquals(List.of("noindex"), full.headers().getValues("X-Robots-Tag"));
    }

    // Every one of these bodies embeds the host of the request (the canonical
    // URL, the "HTML version" line, the MCP endpoint).
    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/privacy", "/resource/people/1", "/about.md", "/resource/films/1.md",
            "/llms-full.txt"})
    void validatorDiffersPerSiteRoot(String path) {
        String tag = tagOf(path);
        Response other = on("other.example").header("If-None-Match", tag).when().get(path);
        Assertions.assertEquals(200, other.statusCode(), path + ": the tag of another host must not match");
        Assertions.assertNotEquals(tag, other.header("ETag"), path);
        Assertions.assertTrue(other.asString().contains("https://other.example"), path);
    }

    @Test
    void validatorDiffersPerContent() {
        List<String> tags = List.of(tagOf("/"), tagOf("/docs"), tagOf("/about"), tagOf("/resource/people/1"),
                tagOf("/resource/people/2"), tagOf("/about.md"), tagOf("/docs.md"), tagOf("/llms-full.txt"));
        Assertions.assertEquals(tags.size(), tags.stream().distinct().count(), tags.toString());
    }

    // RFC 9110, 13.1.2: a list, weak comparison, and "*" for any representation.
    @ParameterizedTest
    @ValueSource(strings = {"/docs", "/docs.md", "/resource/films/1.md", "/llms-full.txt"})
    void listsWeakTagsAndTheStarMatch(String path) {
        String tag = tagOf(path);
        for (String sent : List.of("W/" + tag, "\"other\", " + tag, "\"a,b\" ,W/\"c\"," + tag, "*")) {
            Response response = preview().header("If-None-Match", sent).when().get(path);
            Assertions.assertEquals(304, response.statusCode(), path + " If-None-Match: " + sent);
        }
        Response twoLines = preview().header("If-None-Match", "\"other\"").header("If-None-Match", tag)
                .when().get(path);
        Assertions.assertEquals(304, twoLines.statusCode(), path + " with two header lines");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/docs", "/docs.md", "/resource/films/1.md", "/llms-full.txt"})
    void anotherOrAMalformedTagGetsTheWholeBody(String path) {
        String tag = tagOf(path);
        String bare = tag.substring(1, tag.length() - 1);
        for (String sent : List.of("\"other\"", "W/\"other\"", bare, "\"" + bare, "", "\"x" + bare + "\"")) {
            Response response = preview().header("If-None-Match", sent).when().get(path);
            Assertions.assertEquals(200, response.statusCode(), path + " If-None-Match: " + sent);
            Assertions.assertEquals(tag, response.header("ETag"), path);
            Assertions.assertEquals(tag, digestTag(response.asByteArray()), path + ": the whole body");
        }
    }

    // An error or a redirect is not a representation to confirm.
    @ParameterizedTest
    @ValueSource(strings = {"/does-not-exist", "/resource/people/9999", "/resource/droids", "/docs/webmcp.md",
            "/resource/people/9999.md"})
    void notFoundHasNoValidatorAndIsNeverNotModified(String path) {
        for (Response response : List.of(preview().when().get(path),
                preview().header("If-None-Match", "*").when().get(path))) {
            Assertions.assertEquals(404, response.statusCode(), path);
            Assertions.assertEquals(List.of(), response.headers().getValues("ETag"), path);
            Assertions.assertEquals(List.of("no-cache"), response.headers().getValues("Cache-Control"), path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.html", "/docs/", "/resource/films/1/"})
    void redirectHasNoValidatorAndIsNeverNotModified(String path) {
        for (Response response : List.of(preview().when().get(path),
                preview().header("If-None-Match", "*").when().get(path))) {
            Assertions.assertEquals(301, response.statusCode(), path);
            Assertions.assertEquals(List.of(), response.headers().getValues("ETag"), path);
        }
    }

    // Only GET and HEAD are conditional here; a write to a page is not a page.
    @Test
    void postIsNeverNotModified() {
        Response response = preview().header("If-None-Match", "*").when().post("/about");
        Assertions.assertNotEquals(304, response.statusCode());
        Assertions.assertEquals(List.of(), response.headers().getValues("ETag"));
    }
}
