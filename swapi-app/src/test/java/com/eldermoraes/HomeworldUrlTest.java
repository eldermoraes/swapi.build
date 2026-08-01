package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
public class HomeworldUrlTest {

    // homeworld deve sair absoluto como todos os outros links (films, starships, url...)
    @Test
    public void peopleHomeworldIsAbsolute() {
        given().when().get("/api/people/1")
                .then().statusCode(200)
                .body("homeworld", equalTo("http://localhost:8081/api/planets/1"));
    }
}
