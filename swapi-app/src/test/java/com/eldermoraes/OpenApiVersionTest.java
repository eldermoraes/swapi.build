package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The published spec version must equal the released version. Quarkus derives
 * info.version from the pom today; this test exists so that hardcoding
 * quarkus.smallrye-openapi.info-version can never silently desync the public
 * spec from the releases.
 */
@QuarkusTest
class OpenApiVersionTest {

    @Test
    void publishedSpecVersionMatchesThePomVersion() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body("info.version", is(ReleaseMetadata.pomVersion()));
    }
}
