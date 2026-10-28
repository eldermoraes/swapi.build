package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CacheHeadersTest {

    private static final String EDGE_TTL = "s-maxage=31536000";

    // Exact required value, locked down here: in production code it exists
    // exactly once, in the swapi.cache-control.public property.
    private static final String CACHE_CONTROL =
            "public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400";

    // Static data invalidated by deploy: the edge may hold onto it for a long time.
    @Test
    void successfulResourceIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api/people/1")
        .then()
                .statusCode(200)
                .header("Cache-Control", equalTo(CACHE_CONTROL));
    }

    // A nonexistent id only comes into existence in a new deploy, which already
    // invalidates the cache. Caching 404 is what absorbs id scans.
    @Test
    void notFoundIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api/people/9999")
        .then()
                .statusCode(404)
                .header("Cache-Control", containsString(EDGE_TTL));
    }

    // Caching /random would make the edge always return the same draw.
    @Test
    void everyRandomEndpointStaysUncached() {
        List<String> resources =
                List.of("people", "films", "planets", "species", "starships", "vehicles");

        for (String resource : resources) {
            String path = "/api/" + resource + "/random";
            String cacheControl = given()
                    .when()
                            .get(path)
                    .then()
                            .statusCode(200)
                            .extract().header("Cache-Control");

            Assertions.assertTrue(
                    cacheControl == null || !cacheControl.contains("s-maxage"),
                    path + " must not be cached at the edge, but got: " + cacheControl);
        }
    }

    // Only GET/HEAD is cacheable. There are only @GET handlers here, so a POST
    // hits the method and status guards (405) and does not get the header.
    @Test
    void nonGetResponseStaysUncached() {
        String cacheControl = given()
                .when()
                        .post("/api/people")
                .then()
                        .statusCode(405)
                        .extract().header("Cache-Control");

        Assertions.assertTrue(
                cacheControl == null || !cacheControl.contains("s-maxage"),
                "POST /api/people must not be cached at the edge, but got: " + cacheControl);
    }

    // The API root is static too.
    @Test
    void apiRootIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL));
    }

    // The spec is the canonical contract and only changes on deploy. The /docs
    // page fetches this file on every visit, so it is one of the most requested paths.
    @Test
    void openApiSpecIsCacheableAtTheEdge() {
        given()
                .accept("*/*")
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL));
    }

    // The CORS filter echoes the request's Origin and emits no Vary. Without
    // Vary the edge serves one origin's variant (or the no-origin variant) to
    // another client — intermittently broken CORS, and a third party's ACAO
    // stuck at the edge for a year.
    @Test
    void cacheableResponseVariesByOrigin() {
        given()
                .header("Origin", "https://app.example")
        .when()
                .get("/api/people/1")
        .then()
                .statusCode(200)
                .header("Vary", containsString("Origin"));
    }

    @Test
    void cacheableNotFoundAlsoVariesByOrigin() {
        given()
        .when()
                .get("/api/people/9999")
        .then()
                .statusCode(404)
                .header("Vary", containsString("Origin"));
    }

    @Test
    void openApiSpecVariesByOrigin() {
        given()
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .header("Vary", containsString("Origin"));
    }

    // Vary goes inside the SAME if as Cache-Control: the non-cacheable branch
    // must not pick up Vary for free, otherwise the "random is not cacheable"
    // decision gets coupled to the CORS decision.
    @Test
    void nonCacheableRandomDoesNotGetVary() {
        given()
        .when()
                .get("/api/people/random")
        .then()
                .statusCode(200)
                .header("Vary", nullValue());
    }

    // The assets filter's Vary was only guaranteed by a comment. quarkus.http.filter
    // filters run before routing, so the header appears even when the asset
    // does not exist - which is enough to lock down the config.
    @Test
    void assetsFilterEmitsVaryOrigin() {
        given()
        .when()
                .get("/assets/does-not-matter.js")
        .then()
                .header("Vary", containsString("Origin"));
    }

    // --- SPA HTML ---------------------------------------------------------------
    // Exact required value, locked down here: in production code it exists exactly
    // once, in the swapi.cache-control.html property. It is NOT the /api value on
    // purpose: the browser must never keep HTML (max-age=0 + must-revalidate), or a
    // deploy leaves a stale index.html pointing at asset hashes that no longer exist.
    // A shared cache may keep it for 5 minutes only (s-maxage=300, 2.9.1): nothing in
    // front of the origin is invalidated by a deploy, so a long s-maxage would serve
    // HTML pointing at assets the new binary no longer has.
    private static final String HTML_CACHE_CONTROL =
            "public, max-age=0, must-revalidate, s-maxage=300";

    // Every page of seo/site.json, a list, a record and an indexable record (2.10.0).
    // A new page must match quarkus.http.filter.spa.matches (SeoSiteFileTest checks
    // every page of the file), or it only loses cache.
    private static final List<String> SPA_ROUTES = List.of(
            "/", "/docs", "/docs/mcp", "/docs/webmcp", "/faq", "/about", "/privacy", "/terms", "/stats",
            "/resource/planets", "/resource/people/1", "/resource/films/1");

    @Test
    void spaHtmlRoutesAreCacheableAtTheEdgeButNeverInTheBrowser() {
        for (String path : SPA_ROUTES) {
            List<String> values = given()
                    .accept("text/html")
            .when()
                    .get(path)
            .then()
                    .statusCode(200)
                    .contentType(containsString("text/html"))
                    .extract().headers().getValues("Cache-Control");

            Assertions.assertEquals(List.of(HTML_CACHE_CONTROL), values,
                    path + " must carry exactly one Cache-Control with the HTML policy, but got: " + values);
        }
    }

    // The CORS filter echoes the request Origin on HTML too; without Vary the edge
    // would pin one origin's Access-Control-Allow-Origin on the cached page.
    @Test
    void spaHtmlRoutesVaryByOrigin() {
        for (String path : SPA_ROUTES) {
            given()
                    .accept("text/html")
                    .header("Origin", "https://app.example")
            .when()
                    .get(path)
            .then()
                    .statusCode(200)
                    .header("Vary", containsString("Origin"));
        }
    }

    // A 404 page never goes to a shared cache (2.9.1): the path may start to exist in
    // a release, and nothing in front of the origin is invalidated by a deploy. Exactly
    // one value: no-cache.
    @Test
    void unknownPathDoesNotGetTheSpaHtmlPolicy() {
        List<String> values = given()
                .accept("text/html")
        .when()
                .get("/does-not-exist")
        .then()
                .statusCode(404)
                .extract().headers().getValues("Cache-Control");

        Assertions.assertEquals(List.of("no-cache"), values, "/does-not-exist: " + values);
    }

    // These paths ARE matched by the spa filter (/resource/[^/]+...), which stamps the
    // HTML policy before routing; the 404 must replace it, not add to it.
    @Test
    void notFoundEntityOverridesTheSpaHtmlPolicy() {
        for (String path : List.of("/resource/people/9999", "/resource/droids")) {
            List<String> values = given()
                    .accept("text/html")
            .when()
                    .get(path)
            .then()
                    .statusCode(404)
                    .extract().headers().getValues("Cache-Control");

            Assertions.assertEquals(List.of("no-cache"), values, path + ": " + values);
        }
    }

    // CORS echoes the Origin on robots.txt, sitemap.xml and llms.txt too, and they
    // carry s-maxage=86400.
    @Test
    void seoFilesVaryByOrigin() {
        for (String path : List.of("/robots.txt", "/sitemap.xml", "/llms.txt", "/llms-full.txt", "/docs.md")) {
            given()
                    .header("Origin", "https://app.example")
            .when()
                    .get(path)
            .then()
                    .statusCode(200)
                    .header("Vary", containsString("Origin"));
        }
    }

    // The 301 keeps max-age=300: after a rollback no browser holds it for longer.
    @Test
    void indexHtmlRedirectHasTheSeoPolicy() {
        given()
                .redirects().follow(false)
                .header("Origin", "https://app.example")
        .when()
                .get("/index.html")
        .then()
                .statusCode(301)
                .header("Cache-Control", equalTo("public, max-age=300, s-maxage=86400"))
                .header("Vary", containsString("Origin"));
    }

    // quarkus.http.filter runs before routing, so without the methods=GET,HEAD
    // restriction the SPA filter would stamp the edge TTL on the answer to a
    // write request against a SPA path.
    @Test
    void nonGetOnASpaRouteStaysUncached() {
        String cacheControl = given()
                .accept("text/html")
        .when()
                .post("/about")
        .then()
                .extract().header("Cache-Control");

        Assertions.assertTrue(
                cacheControl == null || !cacheControl.contains("s-maxage"),
                "POST /about must not be cached at the edge, but got: " + cacheControl);
    }
}
