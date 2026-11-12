package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

/**
 * Issue #55: a record has one address, the one in its {@code url}. Another
 * spelling of the id (leading zeros, a sign, a percent-encoded character, a
 * matrix parameter) used to answer 200 with the same record; it is the 404 of
 * the contract now, as on the pages (/resource/people/01) and in MCP
 * (swapi://people/01).
 *
 * Each row is a type, the form its messages use and an id that exists.
 */
@QuarkusTest
class NonCanonicalIdRegressionTest {

    private static final String TYPES = """
            people,    people,   1
            films,     film,     1
            planets,   planet,   1
            species,   specie,   1
            starships, starship, 2
            vehicles,  vehicle,  4
            """;

    // The path goes on the wire as written here: REST Assured would otherwise
    // encode "+" and encode the "%" of "%31" a second time.
    private static ValidatableResponse get(String path) {
        return given().urlEncodingEnabled(false).when().get(path).then();
    }

    /** The spellings of an id that are not its canonical form. */
    private static List<String> aliases(String id) {
        return List.of(
                "0" + id,          // leading zero
                "00" + id,
                "+" + id,          // sign
                "%2B" + id,        // the sign, percent-encoded
                "%3" + id,         // the digit, percent-encoded (ids here have one digit)
                id + ";x=y");      // matrix parameter
    }

    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void aliasOfAnExistingIdIsTheContract404(String type, String form, String id) {
        for (String alias : aliases(id)) {
            String path = "/api/" + type + "/" + alias;
            String body = get(path)
                    .statusCode(404)
                    .contentType("text/plain")
                    .extract().asString();
            Assertions.assertEquals("No " + form + " found with id " + alias, body, path);
        }
    }

    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void canonicalIdStillFindsTheRecord(String type, String form, String id) {
        get("/api/" + type + "/" + id)
                .statusCode(200)
                .contentType("application/json")
                .body("url", endsWith("/api/" + type + "/" + id));
    }

    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void randomStillAnswersAndStaysUncached(String type, String form, String id) {
        String cacheControl = get("/api/" + type + "/random")
                .statusCode(200)
                .contentType("application/json")
                .body("url", containsString("/api/" + type + "/"))
                .extract().header("Cache-Control");
        Assertions.assertTrue(cacheControl == null || !cacheControl.contains("s-maxage"),
                type + "/random must not be cached at the edge, but got: " + cacheControl);
    }

    // An alias is a 404 like any other of /api: cached, so a scan of aliases
    // is absorbed like a scan of ids, and never sniffed.
    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void alias404IsCacheableLikeAnyOther404(String type, String form, String id) {
        get("/api/" + type + "/0" + id)
                .statusCode(404)
                .header("Cache-Control",
                        equalTo("public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400"))
                .header("X-Content-Type-Options", "nosniff");
    }

    // HEAD is what monitors send; the rule does not depend on the method.
    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void headOfAnAliasIs404(String type, String form, String id) {
        given().urlEncodingEnabled(false).when().head("/api/" + type + "/0" + id).then().statusCode(404);
    }

    // Not part of #55: a trailing slash after a canonical id answered 200
    // before and still does. Whoever decides otherwise changes this test.
    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void trailingSlashIsLeftAsItWas(String type, String form, String id) {
        get("/api/" + type + "/" + id + "/")
                .statusCode(200)
                .body("url", endsWith("/api/" + type + "/" + id));
    }

    // ...and a trailing slash does not turn an alias into an address.
    @ParameterizedTest
    @CsvSource(textBlock = TYPES)
    void aliasWithTrailingSlashIs404(String type, String form, String id) {
        get("/api/" + type + "/0" + id + "/")
                .statusCode(404)
                .contentType("text/plain")
                .body(equalTo("No " + form + " found with id 0" + id));
    }
}
