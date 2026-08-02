package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OpenApiContractTest {

    // Task 3 cobre "people"; Tasks 4 ampliam o @ValueSource para os demais
    @ParameterizedTest
    @ValueSource(strings = {"people"})
    void resourceOperationsAreFullyDocumented(String resource) {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                // list + search
                .body("paths.'/api/" + resource + "'.get.summary", not(emptyOrNullString()))
                .body("paths.'/api/" + resource + "'.get.parameters.find { it.name == 'search' }.description",
                        not(emptyOrNullString()))
                // by-id: contrato 200/404 explicito
                .body("paths.'/api/" + resource + "/{id}'.get.responses.'200'", notNullValue())
                .body("paths.'/api/" + resource + "/{id}'.get.responses.'404'.description", not(emptyOrNullString()))
                .body("paths.'/api/" + resource + "/{id}'.get.parameters.find { it.name == 'id' }.description",
                        not(emptyOrNullString()))
                // random
                .body("paths.'/api/" + resource + "/random'.get.summary", not(emptyOrNullString()));
    }
}
