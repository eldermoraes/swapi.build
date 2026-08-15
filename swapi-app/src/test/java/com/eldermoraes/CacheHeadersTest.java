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
}
