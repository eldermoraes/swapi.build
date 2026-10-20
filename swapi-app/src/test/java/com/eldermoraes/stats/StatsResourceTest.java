package com.eldermoraes.stats;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class StatsResourceTest {

    @Test
    void countsRequestsByRouteTemplate() {
        given().get("/api/people/1").then().statusCode(200);
        given().get("/api/people/2").then().statusCode(200);
        given().get("/api/people/99999").then().statusCode(404);

        given().when().get("/api/stats").then()
                .statusCode(200)
                .header("Cache-Control", equalTo(StatsResource.CACHE_CONTROL))
                .body("days", equalTo(7))
                .body("daily.size()", equalTo(7))
                .body("countingSince", notNullValue())
                .body("routes.'/api/people/{id}'", greaterThanOrEqualTo(3))
                .body("routes", not(hasKey("/api/people/1")))
                // scripts/verify-deploy.sh greps the raw body for these two strings.
                .body(containsString("\"countingSince\""))
                .body(containsString("\"/api/people/{id}\""));
    }

    @Test
    void responseCarriesNothingAboutClients() {
        String body = given()
                .header("User-Agent", "stats-test-agent/1.0")
                .header("X-Forwarded-For", "203.0.113.7")
                .get("/api/films?search=hope").then().extract().asString();
        org.junit.jupiter.api.Assertions.assertNotNull(body);

        given().when().get("/api/stats").then()
                .statusCode(200)
                .body(not(containsString("203.0.113.7")))
                .body(not(containsString("stats-test-agent")))
                .body(not(containsString("hope")))
                .body("routes.'/api/films'", greaterThanOrEqualTo(1));
    }

    @Test
    void mcpRequestsAnsweredByTheTransportFilterAreCounted() {
        // GET /mcp is answered with 405 by McpTransportFilter (priority 250);
        // the counting filter runs earlier (400), so it still counts.
        given().get("/mcp").then().statusCode(405);

        given().when().get("/api/stats").then()
                .statusCode(200)
                .body("routes.'/mcp'", greaterThanOrEqualTo(1));
    }

    @Test
    void preflightAndHeadAreNotCounted() {
        // The app is shared with the other test classes, so compare before/after.
        long before = mcpCount();
        given().header("Origin", "https://example.org")
                .header("Access-Control-Request-Method", "POST")
                .options("/mcp");
        given().head("/mcp");

        org.junit.jupiter.api.Assertions.assertEquals(before, mcpCount());
    }

    private static long mcpCount() {
        Number n = given().get("/api/stats").then().statusCode(200).extract().path("routes.'/mcp'");
        return n == null ? 0 : n.longValue();
    }

    @Test
    void periodsSelectTheWindowAndTheTrendBuckets() {
        given().get("/api/people/1").then().statusCode(200);

        given().when().get("/api/stats?period=24h").then()
                .statusCode(200)
                .header("Cache-Control", equalTo(StatsResource.CACHE_CONTROL))
                .body("period", equalTo("24h"))
                .body("granularity", equalTo("hour"))
                .body("series.size()", equalTo(24))
                .body("daily", nullValue())
                .body("groups.keySet()", hasItems("rest", "mcp", "pages", "seo", "other"))
                .body("routeGroups.'/api/people/{id}'", equalTo("rest"))
                .body("previous.total", greaterThanOrEqualTo(0))
                .body(containsString("\"countingSince\""));

        given().when().get("/api/stats?period=30d").then()
                .statusCode(200)
                .body("period", equalTo("30d"))
                .body("granularity", equalTo("day"))
                .body("days", equalTo(30))
                .body("series.size()", equalTo(30))
                .body("daily.size()", equalTo(30))
                .body("routes.'/api/people/{id}'", greaterThanOrEqualTo(1));

        given().when().get("/api/stats?period=7d&granularity=hour").then()
                .statusCode(200)
                .body("series.size()", equalTo(168));
    }

    @Test
    void defaultIsSevenDaysPerDay() {
        given().when().get("/api/stats").then()
                .statusCode(200)
                .body("period", equalTo("7d"))
                .body("granularity", equalTo("day"))
                .body("series.size()", equalTo(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"period=1y", "period=24H", "granularity=minute", "period=24h&granularity=day",
            "period=30d&granularity=hour", "period=%3Cscript%3E"})
    void invalidParametersAre400WithAFixedMessage(String query) {
        given().when().get("/api/stats?" + query).then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .body(not(containsString("<script>")))
                .body(not(containsString("1y")));
    }

    @Test
    void theStatsPageIsCountedAsAPage() {
        given().accept("text/html").get("/stats").then().statusCode(200);

        given().when().get("/api/stats").then()
                .statusCode(200)
                .body("routes.'/stats'", greaterThanOrEqualTo(1))
                .body("routeGroups.'/stats'", equalTo("pages"));
    }

    @Test
    void isDocumentedInTheOpenApiSpec() {
        given().when().get("/openapi.json").then()
                .statusCode(200)
                .body("paths.'/api/stats'.get.summary", notNullValue());
    }
}
