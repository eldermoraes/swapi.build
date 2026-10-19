package com.eldermoraes.seo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/** Without CF_WEB_ANALYTICS_TOKEN (the default) no page loads any analytics script. */
@QuarkusTest
class WebAnalyticsDisabledTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/privacy"})
    void noTokenNoBeacon(String path) {
        given().accept("text/html").when().get(path).then()
                .statusCode(200)
                .body(containsString("</body>"))
                .body(not(containsString("cloudflareinsights")));
    }
}
