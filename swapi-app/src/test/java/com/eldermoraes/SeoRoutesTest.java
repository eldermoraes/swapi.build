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

    @Test
    void unknownDocumentRouteIsNoindex() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<meta name=\"robots\" content=\"noindex,follow\""),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/\""),
                        not(containsString("https://preview.example/not-a-real-page"))));
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

    // The fallback and the detail titles are built by concatenation, so the
    // parity test on the frontend (seo-parity.test.ts) pins the same literals.
    @Test
    void unknownDocumentRouteHasTheHomeTitle() {
        given().accept("text/html").when().get("/nao-existe").then()
                .statusCode(200)
                .body(containsString(
                        "<title>SWAPI alternative: free Star Wars API and MCP server | swapi.build</title>"));
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

    // Unknown paths are NOT matched by the SPA cache filter, so they keep the
    // Accept-based behaviour untouched: SeoRoutes steps aside and nothing
    // downstream claims the path.
    @Test
    void unknownPathWithoutAnHtmlAcceptIsUnchanged() {
        given()
                .accept("application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(404)
                .body(not(containsString("rel=\"canonical\"")));
    }

    // The id segment of a detail route is reflected into the title and the description.
    // Now that the HTML is stored at the edge, a reflection regression would be a CACHED
    // XSS — poisoned once, then served to everyone for the whole deployment. Two layers
    // keep the id inert today, and this pins the outer one: rc.normalizedPath() does NOT
    // percent-decode, so the id reaches the SEO block still encoded. If anyone ever
    // decodes it, seoBlock's html() escaping becomes the only guard and this test is what
    // notices — the literal tag must never appear in the body.
    @Test
    void resourceDetailIdIsReflectedInertlyIntoTheCachedHtml() {
        String body = given()
                .accept("text/html")
                .urlEncodingEnabled(false)
        .when()
                .get("/resource/people/%3Cscript%3Ealert(1)%3C%2Fscript%3E")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .extract().body().asString();

        Assertions.assertFalse(body.contains("<script>alert(1)</script>"),
                "the id must never reach the cached HTML as a live tag");
        Assertions.assertTrue(
                body.contains("<title>People #%3Cscript%3Ealert(1)%3C%2Fscript%3E | swapi.build</title>"),
                "the id must be reflected percent-encoded (inert), but the title was: "
                        + body.substring(body.indexOf("<title>"), body.indexOf("</title>") + 8));
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
