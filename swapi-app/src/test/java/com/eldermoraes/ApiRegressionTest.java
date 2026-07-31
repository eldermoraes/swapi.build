package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class ApiRegressionTest {

    @Test
    public void peopleByIdStillAnswers202WithLuke() {
        // 202 e o comportamento historico da API (Response.accepted()) - nao mudar
        given().when().get("/api/people/1")
                .then().statusCode(202)
                .body(containsString("Luke Skywalker"));
    }

    @Test
    public void searchStillWorks() {
        given().when().get("/api/planets?search=tatooine")
                .then().statusCode(202)
                .body(containsString("Tatooine"));
    }

    @Test
    public void randomStillWorks() {
        given().when().get("/api/starships/random")
                .then().statusCode(202)
                .body(containsString("model"));
    }
}
