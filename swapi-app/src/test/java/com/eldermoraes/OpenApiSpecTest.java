package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class OpenApiSpecTest {

    @Test
    void specIsServedAsJsonRegardlessOfAcceptHeader() {
        // Accept genérico (curl/navegador) — a URL diz .json, a resposta TEM que ser JSON
        given()
                .accept("*/*")
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .contentType(containsString("json"))
                .body("openapi", startsWith("3."));
    }

    @Test
    void infoBlockIsComplete() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body("info.title", equalTo("swapi.build — Star Wars API"))
                .body("info.version", not(emptyOrNullString()))
                .body("info.license.name", equalTo("Apache 2.0"))
                .body("info.description", containsString("/mcp"));
    }

    @Test
    void specHasNoAbsoluteServerUrls() {
        String body = given().accept("*/*")
                .when().get("/openapi.json")
                .then().statusCode(200)
                .extract().asString();

        List<Map<String, Object>> servers = new JsonPath(body).getList("servers");
        if (servers != null) {
            for (Map<String, Object> server : servers) {
                String url = String.valueOf(server.get("url"));
                Assertions.assertFalse(url.startsWith("http://") || url.startsWith("https://"),
                        "servers nao pode conter URL absoluta (base URL e por request): " + url);
            }
        }
    }
}
