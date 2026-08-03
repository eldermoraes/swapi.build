package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CacheHeadersTest {

    private static final String EDGE_TTL = "s-maxage=31536000";

    // Valor exato exigido, travado aqui: em codigo de producao ele existe uma
    // unica vez, na propriedade swapi.cache-control.public.
    private static final String CACHE_CONTROL =
            "public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400";

    // Dado estatico invalidado por deploy: a borda pode guardar por muito tempo.
    @Test
    void successfulResourceIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api/people/1")
        .then()
                .statusCode(200)
                .header("Cache-Control", equalTo(CACHE_CONTROL));
    }

    // Id inexistente so passa a existir num deploy novo, que ja invalida o cache.
    // Cachear 404 e o que absorve varredura de ids.
    @Test
    void notFoundIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api/people/9999")
        .then()
                .statusCode(404)
                .header("Cache-Control", containsString(EDGE_TTL));
    }

    // Cachear /random faria a borda devolver sempre o mesmo sorteio.
    @Test
    void everyRandomEndpointStaysUncached() {
        List<String> resources =
                List.of("people", "films", "planets", "species", "starships", "vehicles");

        for (String resource : resources) {
            String path = "/api/" + resource + "/random";
            String cacheControl = given()
                    .when()
                            .get(path)
                    .then()
                            .statusCode(200)
                            .extract().header("Cache-Control");

            Assertions.assertTrue(
                    cacheControl == null || !cacheControl.contains("s-maxage"),
                    path + " nao pode ser cacheado na borda, mas veio: " + cacheControl);
        }
    }

    // So GET/HEAD e cacheavel. Aqui so ha handlers @GET, entao um POST esbarra
    // nos guards de metodo e de status (405) e nao recebe o header.
    @Test
    void nonGetResponseStaysUncached() {
        String cacheControl = given()
                .when()
                        .post("/api/people")
                .then()
                        .statusCode(405)
                        .extract().header("Cache-Control");

        Assertions.assertTrue(
                cacheControl == null || !cacheControl.contains("s-maxage"),
                "POST /api/people nao pode ser cacheado na borda, mas veio: " + cacheControl);
    }

    // A raiz da API tambem e estatica.
    @Test
    void apiRootIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL));
    }

    // A spec e o contrato canonico e so muda em deploy. A pagina /docs busca
    // esse arquivo a cada visita, entao ele e um dos paths mais requisitados.
    @Test
    void openApiSpecIsCacheableAtTheEdge() {
        given()
                .accept("*/*")
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL));
    }
}
