package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OpenApiContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
    void resourceOperationsAreFullyDocumented(String resource) {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                // list + search
                .body("paths.'/api/" + resource + "'.get.summary", not(emptyOrNullString()))
                .body("paths.'/api/" + resource + "'.get.parameters.find { it.name == 'search' }.description",
                        not(emptyOrNullString()))
                // by-id: explicit 200/404 contract
                .body("paths.'/api/" + resource + "/{id}'.get.responses.'200'", notNullValue())
                .body("paths.'/api/" + resource + "/{id}'.get.responses.'404'.description", not(emptyOrNullString()))
                .body("paths.'/api/" + resource + "/{id}'.get.parameters.find { it.name == 'id' }.description",
                        not(emptyOrNullString()))
                // random
                .body("paths.'/api/" + resource + "/random'.get.summary", not(emptyOrNullString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"People", "Film", "Planet", "Specie", "Starship", "Vehicle"})
    void schemaIsDescribedAndClean(String schemaName) {
        String base = "components.schemas." + schemaName;
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body(base + ".description", not(emptyOrNullString()))
                // every exposed field has a description
                .body(base + ".properties.every { it.value.description != null && !it.value.description.isEmpty() }",
                        org.hamcrest.Matchers.is(true))
                // baseUrl is an internal serialization detail, never part of the contract
                .body(base + ".properties.baseUrl", org.hamcrest.Matchers.nullValue())
                // url is always present (resource identity)
                .body(base + ".properties.url.description", not(emptyOrNullString()));
    }

    @org.junit.jupiter.api.Test
    void rootOperationIsDocumented() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                // the generator materializes the root as "/api" (no trailing slash)
                .body("paths.'/api'.get.summary", not(emptyOrNullString()));
    }

    @org.junit.jupiter.api.Test
    void everyApiPathIsPresentInSpec() {
        String body = given().accept("*/*")
                .when().get("/openapi.json")
                .then().statusCode(200)
                .extract().asString();

        Set<String> paths = new io.restassured.path.json.JsonPath(body).getMap("paths").keySet()
                .stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());

        Set<String> expected = new HashSet<>();
        for (String r : new String[]{"people", "films", "planets", "species", "starships", "vehicles"}) {
            expected.add("/api/" + r);
            expected.add("/api/" + r + "/{id}");
            expected.add("/api/" + r + "/random");
        }

        org.junit.jupiter.api.Assertions.assertTrue(paths.containsAll(expected),
                "Paths missing from spec: " + expected.stream().filter(p -> !paths.contains(p)).toList());
        // root (the exact form /api or /api/ depends on the generator)
        org.junit.jupiter.api.Assertions.assertTrue(paths.contains("/api/") || paths.contains("/api"),
                "Root path missing from spec");
    }
}
