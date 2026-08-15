package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class FilmIdSemanticsTest {

    // The dataset emits "url": ".../films/1" for A New Hope — the endpoint has
    // to honor the link the API itself publishes (record id, not episode id).
    @Test
    public void filmsIdMatchesEmittedUrl() {
        given().when().get("/api/films/1")
                .then().statusCode(200)
                .body(containsString("A New Hope"));
    }

    @Test
    public void recordIdFourIsThePhantomMenace() {
        given().when().get("/api/films/4")
                .then().statusCode(200)
                .body(containsString("The Phantom Menace"));
    }
}
