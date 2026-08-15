package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class NotFoundRegressionTest {

    // Successes return 200 (202 quirk retired on 2026-08-01);
    // "does not exist" is a real 404, not a 200 with an empty body.
    @Test
    public void unknownFilmIs404() {
        given().when().get("/api/films/9999").then().statusCode(404)
                .body(containsString("No film found with id 9999"));
    }

    @Test
    public void unknownPersonIs404() {
        given().when().get("/api/people/9999").then().statusCode(404)
                .body(containsString("No people found with id 9999"));
    }

    @Test
    public void unknownPlanetIs404() {
        given().when().get("/api/planets/9999").then().statusCode(404)
                .body(containsString("No planet found with id 9999"));
    }

    @Test
    public void unknownSpecieIs404() {
        given().when().get("/api/species/9999").then().statusCode(404)
                .body(containsString("No specie found with id 9999"));
    }

    @Test
    public void unknownStarshipIs404() {
        given().when().get("/api/starships/9999").then().statusCode(404)
                .body(containsString("No starship found with id 9999"));
    }

    @Test
    public void unknownVehicleIs404() {
        given().when().get("/api/vehicles/9999").then().statusCode(404)
                .body(containsString("No vehicle found with id 9999"));
    }
}
