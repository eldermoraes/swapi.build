package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class ForwardedHeadersTest {

    @Test
    public void embeddedUrlsHonorForwardedHostAndProto() {
        // Plain request first: if the baseUrl freezes on the first request
        // (singleton resource constructor), the forwarded one below fails.
        given()
                .when().get("/api/people/1")
                .then().statusCode(200)
                .body(containsString("http://localhost:8081/api/people/1"));

        given()
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "migrated.example")
                .when().get("/api/people/1")
                .then().statusCode(200)
                .body(containsString("https://migrated.example/api/people/1"));
    }
}
