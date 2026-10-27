package com.eldermoraes.seo.ssr;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * swapi.public-base-url is the API base (2.11.0), the way the MCP tools read it
 * (SwapiBaseUrlOverrideTest): the /docs/mcp endpoint comes from the site root
 * derived from it, so it is never .../api/mcp.
 */
@QuarkusTest
@TestProfile(ServerRenderedOverrideTest.OverrideProfile.class)
class ServerRenderedOverrideTest {

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.public-base-url", "https://config-wins.example/api");
        }
    }

    @Test
    void theMcpEndpointIsTheSiteRootPlusMcp() {
        String body = given().accept("text/html")
                .header("X-Forwarded-Host", "preview.example")
                .when().get("/docs/mcp").then().statusCode(200).extract().asString();
        assertTrue(body.contains("claude mcp add --transport http swapi-build https://config-wins.example/mcp"), body);
        assertFalse(body.contains("/api/mcp"), body);
        assertFalse(body.contains("preview.example/mcp"), body);
    }
}
