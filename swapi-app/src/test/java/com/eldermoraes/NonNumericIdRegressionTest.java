package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class NonNumericIdRegressionTest {

    // Issue #12: an id that does not parse as a Java int fails in @PathParam
    // conversion before the method body runs, and the framework's default 404
    // went out with no content-type and no body — violating the text/plain
    // contract in openapi.json. The ApiNotFoundMapper guarantees that EVERY 404
    // in the /api family carries text/plain and a readable message.

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

    // The failing set is not "non-numeric": it is "does not parse as int".
    @Test
    public void intOverflowIdIs404WithBody() {
        assertContractual404("/api/people/2147483648", "No people found with id 2147483648");
    }

    @Test
    public void decimalIdIs404WithBody() {
        assertContractual404("/api/people/1.5", "No people found with id 1.5");
    }

    // A nonexistent /api route also answers the contract, with a generic message.
    @Test
    public void unknownApiRouteIs404WithBody() {
        assertContractual404("/api/wookiees/1", "No resource found at /api/wookiees/1");
    }

    // Positive control (suggested in issue #12): an ExceptionMapper scoped to
    // an entire prefix is the kind of fix that can swallow legitimate
    // responses, and a suite that only pins 404 cannot distinguish a correct
    // mapper from one that captures too much. A valid GET still returns 200
    // application/json with the full record — the mapper is not over-reaching.
    @Test
    public void validIdStillReturns200JsonRecord() {
        given().when().get("/api/people/1").then()
                .statusCode(200)
                .contentType("application/json")
                .body(containsString("Luke Skywalker"))
                .body(containsString("height"))
                .body(containsString("homeworld"));
    }

    // Defense in depth (issue #12): the 404 echoes the id segment verbatim, so
    // every response carries X-Content-Type-Options: nosniff to prevent
    // reinterpretation should an error path return a sniffable type.
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
