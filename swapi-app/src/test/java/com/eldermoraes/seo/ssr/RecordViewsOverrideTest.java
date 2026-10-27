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
 * The override profile of RecordViewsTest (2.12.0; a profile is per class):
 * swapi.public-base-url is the API base (2.11.0), so the record drawn on the
 * server has its URLs on it as they are, never .../api/api/, in the JSON and in
 * the curl sample alike.
 */
@QuarkusTest
@TestProfile(RecordViewsOverrideTest.OverrideProfile.class)
class RecordViewsOverrideTest {

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.public-base-url", "https://config-wins.example/api");
        }
    }

    @Test
    void theRecordIsOnTheOverrideAndNeverApiApi() {
        String body = given().accept("text/html")
                .header("X-Forwarded-Host", "preview.example")
                .when().get("/resource/people/1").then().statusCode(200).extract().asString();
        assertTrue(body.contains("&quot;https://config-wins.example/api/people/1&quot;"), body);
        assertTrue(body.contains("curl https://config-wins.example/api/people/1"), body);
        assertTrue(body.contains("claude mcp add --transport http swapi-build https://config-wins.example/mcp"), body);
        assertFalse(body.contains("/api/api/"), body);
        assertFalse(body.contains("preview.example/api"), body);
    }
}
