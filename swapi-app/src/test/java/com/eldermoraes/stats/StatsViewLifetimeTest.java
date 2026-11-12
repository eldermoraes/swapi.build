package com.eldermoraes.stats;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/stats} with the lifetime production runs with (#53). The
 * rest of the suite runs with a lifetime of zero, so that a test reads the
 * request it just made.
 */
@QuarkusTest
@TestProfile(StatsViewLifetimeTest.OneMinute.class)
class StatsViewLifetimeTest {

    public static class OneMinute implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.stats.view-lifetime", "PT60S");
        }
    }

    private static final Pattern MAX_AGE = Pattern.compile("public, max-age=(\\d+)");

    @Test
    void aViewIsServedAgainAndItsMaxAgeNeverPassesSixtySeconds() {
        given().get("/api/people/1").then().statusCode(200);
        Response first = given().get("/api/stats?period=30d");
        first.then().statusCode(200).body("routes.'/api/people/{id}'", greaterThanOrEqualTo(1));
        assertEquals(StatsResource.CACHE_CONTROL, first.header("Cache-Control"), "a view just built");

        given().get("/api/people/1").then().statusCode(200);
        Response second = given().get("/api/stats?period=30d");

        assertEquals(first.asString(), second.asString(), "the same view, without the request made in between");
        Matcher maxAge = MAX_AGE.matcher(second.header("Cache-Control"));
        assertTrue(maxAge.matches(), second.header("Cache-Control"));
        assertTrue(Integer.parseInt(maxAge.group(1)) <= 60, second.header("Cache-Control"));

        // Another window is another view: built now, it has both requests.
        given().get("/api/stats?period=24h").then()
                .statusCode(200)
                .body("routes.'/api/people/{id}'", greaterThanOrEqualTo(2));
    }
}
