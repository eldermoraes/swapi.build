package com.eldermoraes.seo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/** With a token configured, every SPA document carries the beacon before </body>, and nothing else does. */
@QuarkusTest
@TestProfile(WebAnalyticsBeaconTest.WithToken.class)
class WebAnalyticsBeaconTest {

    static final String TOKEN = "0123456789abcdef0123456789abcdef";

    public static class WithToken implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.analytics.cloudflare-token", TOKEN);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/privacy", "/resource/people/1"})
    void documentsCarryTheBeacon(String path) {
        given().accept("text/html").when().get(path).then()
                .statusCode(200)
                .body(containsString("<script defer src=\"https://static.cloudflareinsights.com/beacon.min.js\" "
                        + "data-cf-beacon='{\"token\": \"" + TOKEN + "\"}'></script>\n  </body>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/people/1", "/openapi.json", "/robots.txt", "/llms.txt", "/api/stats",
            "/docs.md", "/resource/films/1.md", "/llms-full.txt"})
    void nonDocumentsDoNot(String path) {
        given().when().get(path).then()
                .statusCode(200)
                .body(not(containsString("cloudflareinsights")));
    }

    // The 404 page is a document (2.9.1): Web Analytics shows the visits to it.
    @Test
    void notFoundDocumentCarriesTheBeacon() {
        given().accept("text/html").when().get("/not-a-real-page").then()
                .statusCode(404)
                .body(containsString("<script defer src=\"https://static.cloudflareinsights.com/beacon.min.js\" "
                        + "data-cf-beacon='{\"token\": \"" + TOKEN + "\"}'></script>\n  </body>"));
    }

    // #58: the validator is of the bytes sent, beacon included, so turning the
    // beacon on or off on the host (a restart, not a release) changes it. The
    // document without the beacon is the one the site serves with no token:
    // SiteShell puts "  <beacon>\n  " before the closing body tag.
    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/privacy", "/resource/people/1"})
    void validatorCoversTheBeacon(String path) {
        Response response = given().accept("text/html").when().get(path);
        String body = response.asString();
        String beacon = "  <script defer src=\"https://static.cloudflareinsights.com/beacon.min.js\" "
                + "data-cf-beacon='{\"token\": \"" + TOKEN + "\"}'></script>\n  </body>";
        Assertions.assertTrue(body.contains(beacon), path);
        Assertions.assertEquals(TaggedBody.of(body).etag(), response.header("ETag"), path);
        Assertions.assertNotEquals(TaggedBody.of(body.replace(beacon, "</body>")).etag(), response.header("ETag"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.html"})
    void redirectsDoNot(String path) {
        given().redirects().follow(false).when().get(path).then()
                .statusCode(301)
                .body(not(containsString("cloudflareinsights")));
    }
}
