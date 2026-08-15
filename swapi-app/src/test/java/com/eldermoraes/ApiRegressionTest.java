package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class ApiRegressionTest {

    @Test
    public void peopleByIdAnswers200WithLuke() {
        // 200 is the current contract (Response.ok()); the historic 202 was retired on 2026-08-01
        given().when().get("/api/people/1")
                .then().statusCode(200)
                .body(containsString("Luke Skywalker"));
    }

    @Test
    public void searchStillWorks() {
        given().when().get("/api/planets?search=tatooine")
                .then().statusCode(200)
                .body(containsString("Tatooine"));
    }

    @Test
    public void randomStillWorks() {
        given().when().get("/api/starships/random")
                .then().statusCode(200)
                .body(containsString("model"));
    }
}
