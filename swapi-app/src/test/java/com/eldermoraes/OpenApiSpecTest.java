package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class OpenApiSpecTest {

    @Test
    void specIsServedAsJsonRegardlessOfAcceptHeader() {
        // Accept genérico (curl/navegador) — a URL diz .json, a resposta TEM que ser JSON
        given()
                .accept("*/*")
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .contentType(containsString("json"))
                .body("openapi", startsWith("3."));
    }
}
