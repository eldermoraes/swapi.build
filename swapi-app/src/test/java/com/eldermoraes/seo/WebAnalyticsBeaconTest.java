package com.eldermoraes.seo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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

    @ParameterizedTest
    @ValueSource(strings = {"/index.html"})
    void redirectsDoNot(String path) {
        given().redirects().follow(false).when().get(path).then()
                .statusCode(301)
                .body(not(containsString("cloudflareinsights")));
    }
}
