package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class NonNumericIdRegressionTest {

    // Id nao numerico nunca e um erro de servidor: a conversao JAX-RS do
    // @PathParam int responde 404, igual ao comportamento de films.
    @Test
    public void nonNumericFilmIdIs404() {
        given().when().get("/api/films/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericPersonIdIs404() {
        given().when().get("/api/people/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericPlanetIdIs404() {
        given().when().get("/api/planets/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericSpecieIdIs404() {
        given().when().get("/api/species/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericStarshipIdIs404() {
        given().when().get("/api/starships/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericVehicleIdIs404() {
        given().when().get("/api/vehicles/abc").then().statusCode(404);
    }
}
