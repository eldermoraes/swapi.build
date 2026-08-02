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
                // by-id: contrato 200/404 explicito
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
                // todo campo exposto tem description
                .body(base + ".properties.every { it.value.description != null && !it.value.description.isEmpty() }",
                        org.hamcrest.Matchers.is(true))
                // baseUrl e detalhe interno de serializacao, nunca parte do contrato
                .body(base + ".properties.baseUrl", org.hamcrest.Matchers.nullValue())
                // url sempre presente (identidade do recurso)
                .body(base + ".properties.url.description", not(emptyOrNullString()));
    }

    @org.junit.jupiter.api.Test
    void rootOperationIsDocumented() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                // o gerador materializa o root como "/api" (sem barra final)
                .body("paths.'/api'.get.summary", not(emptyOrNullString()));
    }
}
