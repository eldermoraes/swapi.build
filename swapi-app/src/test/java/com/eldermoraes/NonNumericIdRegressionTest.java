package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class NonNumericIdRegressionTest {

    // Issue #12: um id que nao parseia como int Java falha na conversao do
    // @PathParam antes do corpo do metodo rodar, e o 404 default do framework
    // saia sem content-type e sem corpo — violando o contrato text/plain do
    // openapi.json. O ApiNotFoundMapper garante que TODO 404 da familia /api
    // carrega text/plain e uma mensagem legivel.

    private void assertContractual404(String path, String expectedFragment) {
        given().when().get(path).then()
                .statusCode(404)
                .contentType("text/plain")
                .body(containsString(expectedFragment));
    }

    @Test
    public void nonNumericFilmIdIs404() {
        assertContractual404("/api/films/abc", "No film found with id abc");
    }

    @Test
    public void nonNumericPersonIdIs404() {
        assertContractual404("/api/people/abc", "No people found with id abc");
    }

    @Test
    public void nonNumericPlanetIdIs404() {
        assertContractual404("/api/planets/abc", "No planet found with id abc");
    }

    @Test
    public void nonNumericSpecieIdIs404() {
        assertContractual404("/api/species/abc", "No specie found with id abc");
    }

    @Test
    public void nonNumericStarshipIdIs404() {
        assertContractual404("/api/starships/abc", "No starship found with id abc");
    }

    @Test
    public void nonNumericVehicleIdIs404() {
        assertContractual404("/api/vehicles/abc", "No vehicle found with id abc");
    }

    // O conjunto que falha nao e "nao numerico": e "nao parseia como int".
    @Test
    public void intOverflowIdIs404WithBody() {
        assertContractual404("/api/people/2147483648", "No people found with id 2147483648");
    }

    @Test
    public void decimalIdIs404WithBody() {
        assertContractual404("/api/people/1.5", "No people found with id 1.5");
    }

    // Rota /api inexistente tambem responde o contrato, com mensagem generica.
    @Test
    public void unknownApiRouteIs404WithBody() {
        assertContractual404("/api/wookiees/1", "No resource found at /api/wookiees/1");
    }

    // Controle positivo (sugerido na issue #12): um ExceptionMapper escopado a
    // um prefixo inteiro e o tipo de fix que pode engolir respostas legitimas,
    // e uma suite que so pina 404 nao distingue um mapper correto de um que
    // captura demais. Um GET valido continua 200 application/json com o
    // registro completo — o mapper nao esta over-reaching.
    @Test
    public void validIdStillReturns200JsonRecord() {
        given().when().get("/api/people/1").then()
                .statusCode(200)
                .contentType("application/json")
                .body(containsString("Luke Skywalker"))
                .body(containsString("height"))
                .body(containsString("homeworld"));
    }

    // Defesa em profundidade (issue #12): o 404 ecoa o segmento de id
    // verbatim, entao toda resposta carrega X-Content-Type-Options: nosniff
    // para impedir reinterpretacao caso um error path devolva tipo sniffavel.
    @Test
    public void notFoundCarriesNosniffHeader() {
        given().when().get("/api/people/abc").then()
                .statusCode(404)
                .header("X-Content-Type-Options", "nosniff");
    }

    @Test
    public void successCarriesNosniffHeader() {
        given().when().get("/api/people/1").then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff");
    }
}
