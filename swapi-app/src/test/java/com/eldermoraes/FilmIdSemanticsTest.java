package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class FilmIdSemanticsTest {

    // O dataset emite "url": ".../films/1" para A New Hope — o endpoint tem
    // que honrar o link que a propria API publica (record id, nao episode id).
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
