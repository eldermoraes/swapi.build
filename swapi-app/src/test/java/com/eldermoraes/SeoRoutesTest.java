package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class SeoRoutesTest {

    @Test
    void webMcpGuideHasItsOwnMetadata() {
        given().accept("text/html").when().get("/docs/webmcp").then()
                .statusCode(200).contentType(containsString("text/html"))
                .body(containsString("<title>Star Wars WebMCP tools in the browser | swapi.build</title>"));
    }

    @Test
    void robotsTxtBypassesSpaFallbackAndUsesForwardedBaseUrl() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/robots.txt")
        .then()
                .statusCode(200)
                .contentType(containsString("text/plain"))
                .body(allOf(
                        containsString("User-agent: *"),
                        containsString("Allow: /"),
                        containsString("Sitemap: https://preview.example/sitemap.xml"),
                        not(containsString("<!doctype html>"))));
    }

    @Test
    void llmsTxtUsesTheForwardedBaseUrl() {
        String body = given()
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/llms.txt")
        .then()
                .statusCode(200)
                .contentType(containsString("text/plain"))
                .extract().body().asString();

        Assertions.assertTrue(body.startsWith("# swapi.build"), body);
        // Episodes I-VI are the prequel and original trilogies, not the "original saga".
        Assertions.assertTrue(body.contains("the six films of Episodes I-VI"), body);
        Assertions.assertFalse(body.contains("original"), body);
        Assertions.assertTrue(body.contains("https://preview.example/mcp"), body);
        Assertions.assertTrue(body.contains("https://preview.example/openapi.json"), body);
        Assertions.assertTrue(body.contains(
                "claude mcp add --transport http swapi-build https://preview.example/mcp"), body);
        Assertions.assertFalse(body.contains("https://swapi.build"), body);
        Assertions.assertFalse(body.contains("<resource>"), body);
    }

    // Regression of diagnosis 3 in the SEO/AEO study: /llms.txt fell into the SPA
    // shell (200 text/html, index,follow).
    @Test
    void llmsTxtIsTextEvenWithAnHtmlAccept() {
        given()
                .accept("text/html")
        .when()
                .get("/llms.txt")
        .then()
                .statusCode(200)
                .contentType(containsString("text/plain"))
                .body(allOf(
                        not(containsString("<html")),
                        not(containsString("index,follow"))));
    }

    @Test
    void llmsTxtHasTheSeoCachePolicy() {
        given()
        .when()
                .get("/llms.txt")
        .then()
                .statusCode(200)
                .header("Cache-Control", "public, max-age=300, s-maxage=86400");
    }

    @Test
    void sitemapXmlBypassesSpaFallbackAndUsesForwardedBaseUrl() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/sitemap.xml")
        .then()
                .statusCode(200)
                .contentType(containsString("xml"))
                .body(allOf(
                        containsString("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"),
                        containsString("<loc>https://preview.example/</loc>"),
                        containsString("<loc>https://preview.example/docs</loc>"),
                        containsString("<loc>https://preview.example/docs/mcp</loc>"),
                        containsString("<loc>https://preview.example/docs/webmcp</loc>"),
                        containsString("<loc>https://preview.example/about</loc>"),
                        containsString("<loc>https://preview.example/resource/people</loc>"),
                        containsString("<loc>https://preview.example/resource/films</loc>"),
                        containsString("<loc>https://preview.example/resource/planets</loc>"),
                        containsString("<loc>https://preview.example/resource/species</loc>"),
                        containsString("<loc>https://preview.example/resource/starships</loc>"),
                        containsString("<loc>https://preview.example/resource/vehicles</loc>"),
                        not(containsString("<!doctype html>"))));
    }

    @Test
    void homeHtmlHasServerRenderedSeoMetadata() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<title>SWAPI alternative: free Star Wars API and MCP server | swapi.build</title>"),
                        containsString("<meta name=\"description\""),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/\""),
                        containsString("<meta property=\"og:url\" content=\"https://preview.example/\""),
                        containsString("<meta property=\"og:image\" content=\"https://preview.example/og-image.png\""),
                        containsString("<meta name=\"twitter:card\" content=\"summary_large_image\""),
                        containsString("<script type=\"application/ld+json\""),
                        containsString("schema.org"),
                        not(containsString("https://swapi.build"))));
    }

    @Test
    void docsHtmlHasRouteSpecificCanonical() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/docs")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<title>SWAPI docs: Star Wars REST API endpoints and OpenAPI | swapi.build</title>"),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/docs\""),
                        containsString("<meta property=\"og:url\" content=\"https://preview.example/docs\""),
                        containsString("OpenAPI"),
                        not(containsString("All the Star Wars data you've ever wanted: People"))));
    }

    // 2.9.1: an unknown page is a real 404 (it was 200 with the home page, a soft
    // 404), noindex, with no canonical and no URL built from the request host.
    @Test
    void unknownDocumentRouteIsNoindex() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(404)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<meta name=\"robots\" content=\"noindex\" />"),
                        containsString("<meta name=\"swapi:status\" content=\"404\" />"),
                        not(containsString("rel=\"canonical\"")),
                        not(containsString("preview.example"))));
    }

    @Test
    void ogSiteNameIsTheBrand() {
        given().accept("text/html").when().get("/").then()
                .statusCode(200)
                .body(allOf(
                        containsString("<meta property=\"og:site_name\" content=\"swapi.build\" />"),
                        containsString("\"@type\":\"WebSite\",\"name\":\"swapi.build\""),
                        containsString("\"@type\":\"SoftwareApplication\",\"name\":\"swapi.build\"")));
    }

    // The not-found title is a constant (NOT_FOUND_TITLE) that the parity test on
    // the frontend (seo-parity.test.ts) compares with seo.ts.
    @Test
    void unknownDocumentRouteHasTheNotFoundTitle() {
        given().accept("text/html").when().get("/nao-existe").then()
                .statusCode(404)
                .body(containsString("<title>Page not found | swapi.build</title>"));
    }

    // The singular noun is the modifier: "film records", not "films records".
    @Test
    void resourceDescriptionsReadAsEnglish() {
        given().accept("text/html").when().get("/resource/films").then()
                .statusCode(200)
                .body(allOf(
                        containsString("<meta name=\"description\" content=\"Browse Star Wars film records as JSON "
                                + "from the free swapi.build REST API and MCP server.\" />"),
                        not(containsString("films records"))));
        given().accept("text/html").when().get("/resource/films/3").then()
                .statusCode(200)
                .body(allOf(
                        containsString("<title>Films #3 | swapi.build</title>"),
                        containsString("<meta name=\"description\" content=\""
                                + "Star Wars film record #3 from the swapi.build REST API.\" />")));
    }

    @Test
    void resourceDetailHasItsTitleAndDescription() {
        given().accept("text/html").when().get("/resource/people/1").then()
                .statusCode(200)
                .body(allOf(
                        containsString("<title>People #1 | swapi.build</title>"),
                        containsString("<meta name=\"description\" content=\""
                                + "Star Wars people record #1 from the swapi.build REST API.\" />")));
    }

    // The SPA HTML is stored at the edge by path alone (quarkus.http.filter.spa),
    // and Accept is not part of the cache key. If a known route still answered the
    // raw shell to a non-HTML Accept, the first such request after a deploy would
    // pin a canonical-less, generically titled page at the edge for the whole
    // deployment. Every route the filter matches must have exactly one body.
    @Test
    void knownRouteServesTheSeoDocumentEvenWithoutAnHtmlAccept() {
        given()
                .accept("application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/docs")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<title>SWAPI docs: Star Wars REST API endpoints and OpenAPI | swapi.build</title>"),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/docs\"")));
    }

    @Test
    void knownResourceDetailRouteServesTheSeoDocumentEvenWithoutAnHtmlAccept() {
        given()
                .accept("application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/resource/people/1")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<title>People #1 | swapi.build</title>"),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/resource/people\"")));
    }

    // The Accept header decides nothing (2.9.1): an unknown path is the same 404
    // document for every client. Before, the test answered 404 here only because
    // the suite runs without Quinoa; production rerouted the path to the home page.
    @Test
    void unknownPathIs404WhateverTheAccept() {
        String json = given()
                .accept("application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(404)
                .contentType(containsString("text/html"))
                .body(not(containsString("rel=\"canonical\"")))
                .extract().body().asString();
        String html = given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(404)
                .extract().body().asString();

        Assertions.assertEquals(html, json, "the 404 document must not depend on the Accept header");
    }

    // The id segment of a detail route is reflected into the title and the description
    // of the cacheable HTML, so a reflection regression would be a CACHED XSS. Since
    // 2.9.1 only a canonical id of an entity that exists reaches the title
    // (KnownEntities): this id is not canonical, so it is a 404, and the 404 document
    // reflects nothing of the path, encoded or not.
    @Test
    void resourceDetailIdIsReflectedInertlyIntoTheCachedHtml() {
        String body = given()
                .accept("text/html")
                .urlEncodingEnabled(false)
        .when()
                .get("/resource/people/%3Cscript%3Ealert(1)%3C%2Fscript%3E")
        .then()
                .statusCode(404)
                .contentType(containsString("text/html"))
                .extract().body().asString();

        Assertions.assertFalse(body.contains("<script>alert(1)</script>"),
                "the id must never reach the HTML as a live tag");
        Assertions.assertFalse(body.contains("%3Cscript"),
                "the 404 document must not reflect the path at all");
    }

    // --- 404 and variants (2.9.1) --------------------------------------------

    // The marks of the SPA shell: a framework 404 must carry none of them (the
    // Vert.x default 404 is itself a tiny HTML page, so "no <html" would be wrong).
    private static void assertNotTheShell(String path, String body) {
        for (String mark : new String[] {"id=\"app\"", "id=\"main-content\"", "swapi:status", "<title>"}) {
            Assertions.assertFalse(body.contains(mark), path + " must not answer the SPA shell (" + mark + ")");
        }
    }

    private static void assertNotFoundDocument(String path) {
        String body = given()
                .accept("text/html")
                .urlEncodingEnabled(false)
        .when()
                .get(path)
        .then()
                .statusCode(404)
                .contentType(containsString("text/html"))
                .extract().body().asString();
        Assertions.assertTrue(body.contains("<meta name=\"robots\" content=\"noindex\" />"), path + ": " + body);
        Assertions.assertTrue(body.contains("<meta name=\"swapi:status\" content=\"404\" />"), path);
        Assertions.assertTrue(body.contains("<title>Page not found | swapi.build</title>"), path);
        Assertions.assertFalse(body.contains("rel=\"canonical\""), path);
        Assertions.assertFalse(body.contains("og:"), path);
        Assertions.assertFalse(body.contains("twitter:"), path);
        Assertions.assertFalse(body.contains("application/ld+json"), path);
    }

    // %31 is "1" percent-encoded: Vert.x's normalizedPath() decodes it, the browser's
    // location.pathname does not. Page paths are read raw, so server and client
    // agree that these are not pages.
    @Test
    void nonexistentEntityIs404Noindex() {
        for (String path : new String[] {"/resource/people/9999", "/resource/people/17", "/resource/starships/1",
                "/resource/people/01", "/resource/people/%31", "/resource/peop%6Ce/1"}) {
            assertNotFoundDocument(path);
        }
    }

    @Test
    void trailingSlashStays200() {
        for (String path : new String[] {"/resource/people/", "/resource/people/1/"}) {
            given().accept("text/html").when().get(path).then()
                    .statusCode(200)
                    .body(containsString("<link rel=\"canonical\""));
        }
    }

    @Test
    void unknownResourceTypeIs404() {
        for (String path : new String[] {"/resource/droids", "/resource/droids/1", "/resource/foo.json",
                "/resource/people/1/x"}) {
            assertNotFoundDocument(path);
        }
    }

    @Test
    void wellKnownIsFrameworkNotFound() {
        String path = "/.well-known/oauth-protected-resource";
        String body = given().when().get(path).then().statusCode(404).extract().body().asString();
        assertNotTheShell(path, body);
    }

    @Test
    void subpathsOfKnownPagesAre404() {
        for (String path : new String[] {"/docs/nope", "/about/x", "/stats/x", "/openapi.json/x", "/doc%73"}) {
            assertNotFoundDocument(path);
        }
    }

    // /apix used to pass through as backend (startsWith("/api")).
    @Test
    void apiLookalikeIs404Document() {
        assertNotFoundDocument("/apix");
    }

    @Test
    void indexHtmlRedirectsHome() {
        given().redirects().follow(false).when().get("/index.html").then()
                .statusCode(301)
                .header("Location", "/");
        given().redirects().follow(false).when().get("/index.html?x=1").then()
                .statusCode(301)
                .header("Location", "/?x=1");
        given().redirects().follow(false).when().head("/index.html").then()
                .statusCode(301)
                .header("Location", "/");
    }

    // Guard: in the suite this is a 404 already (no Quinoa); in production the
    // config (QuinoaConfigTest) is what keeps it from being rerouted to the home.
    @Test
    void dottedUnknownPathIsNotTheShell() {
        for (String path : new String[] {"/foo.json", "/.well-known/mcp.json"}) {
            String body = given().accept("text/html").when().get(path).then()
                    .statusCode(404)
                    .extract().body().asString();
            Assertions.assertFalse(body.contains("index,follow"), path);
            assertNotTheShell(path, body);
        }
    }

    @Test
    void missingAssetIsNotTheShell() {
        String path = "/assets/does-not-exist.js";
        io.restassured.response.Response response = given().when().get(path);
        Assertions.assertEquals(404, response.statusCode());
        assertNotTheShell(path, response.body().asString());
        Assertions.assertEquals(java.util.List.of("no-cache"), response.headers().getValues("Cache-Control"),
                "a 404 must never carry the year-long immutable policy of the assets");
    }

    // Regression found in the prod-mode rehearsal of 2.9.1: /mcp/foo passed on as
    // backend and its framework 404 left with no Cache-Control at all, against "every
    // 404 of the app leaves with no-cache". The /api 404s keep their edge policy
    // (CacheHeadersTest.notFoundIsCacheableAtTheEdge).
    @Test
    void mcpSubpathNotFoundIsNoCache() {
        String path = "/mcp/foo";
        io.restassured.response.Response response = given().accept("text/html").when().get(path);
        Assertions.assertEquals(404, response.statusCode());
        assertNotTheShell(path, response.body().asString());
        Assertions.assertEquals(java.util.List.of("no-cache"), response.headers().getValues("Cache-Control"));
    }

    @Test
    void knownRoutesStay200() {
        for (String path : new String[] {"/", "/docs", "/docs/", "/resource/people", "/resource/people/1",
                "/resource/films/6", "/resource/people/83", "/resource/starships/2", "/resource/vehicles/4"}) {
            given().accept("text/html").when().get(path).then()
                    .statusCode(200)
                    .body(containsString("<link rel=\"canonical\""));
        }
    }

    @Test
    void notFoundCarriesNoHostDerivedUrl() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "evil.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(404)
                .body(not(containsString("evil.example")));
    }

    // The other layer: a path carrying the raw characters is rejected by the HTTP layer
    // before any of our code runs, so an attacker cannot skip the encoding above. Sent on
    // a raw socket because HTTP clients refuse to put an unencoded '<' in a request line.
    @Test
    void rawAngleBracketsInThePathNeverReachTheSeoInjector() throws Exception {
        try (Socket socket = new Socket("localhost", 8081)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /resource/people/<script>alert(1)</script> HTTP/1.1\r\n"
                    + "Host: probe.example\r\nAccept: text/html\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            Assertions.assertTrue(response.startsWith("HTTP/1.1 400"),
                    "an unencoded '<' in the path must be rejected, but got: "
                            + response.lines().findFirst().orElse("(empty)"));
            Assertions.assertFalse(response.contains("<title>"),
                    "a rejected path must never produce an SEO document");
        }
    }

    @Test
    void ogImageIsServedAsPng() {
        given()
        .when()
                .get("/og-image.png")
        .then()
                .statusCode(200)
                .contentType(containsString("image/png"));
    }
}
