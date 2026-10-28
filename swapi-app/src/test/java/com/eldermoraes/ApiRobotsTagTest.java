package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * The HTML ranks, the JSON does not (2.10.0): every /api response says
 * X-Robots-Tag: noindex, 200 and 404 alike, and nothing else does. The header
 * on the HTML would take the whole site out of the index, so the guards below
 * matter more than the header itself.
 */
@QuarkusTest
class ApiRobotsTagTest {

    private static final String API_CACHE_CONTROL = "public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400";

    @Test
    void everyApiResponseSaysNoindex() {
        for (String path : List.of("/api/people/1", "/api/films/9", "/api/people/random", "/api/people?search=sky",
                "/api", "/api/stats")) {
            Response get = given().redirects().follow(false).when().get(path);
            Assertions.assertEquals(List.of("noindex"), get.headers().getValues("X-Robots-Tag"), "GET " + path);
            Response head = given().redirects().follow(false).when().head(path);
            Assertions.assertEquals(List.of("noindex"), head.headers().getValues("X-Robots-Tag"), "HEAD " + path);
        }
    }

    // Guard, the most important one of the release: never on the HTML, the SEO
    // files, the OpenAPI contract, the 404 document or MCP.
    @Test
    void nothingOutsideTheApiSaysNoindexInAHeader() {
        for (String path : List.of("/", "/docs", "/resource/films/1", "/resource/people/1", "/resource/films",
                "/openapi.json", "/robots.txt", "/sitemap.xml", "/llms.txt", "/og-image.png", "/not-a-real-page",
                "/apix", "/resource/people/9999", "/docs/", "/docs.md", "/index.html.md", "/resource/films/1.md")) {
            Response get = given().accept("text/html").redirects().follow(false).when().get(path);
            Assertions.assertEquals(List.of(), get.headers().getValues("X-Robots-Tag"), "GET " + path);
            Response head = given().accept("text/html").redirects().follow(false).when().head(path);
            Assertions.assertEquals(List.of(), head.headers().getValues("X-Robots-Tag"), "HEAD " + path);
        }
    }

    @Test
    void mcpDoesNotSayNoindex() {
        Response response = given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("Mcp-Method", "tools/list")
                .header("MCP-Protocol-Version", "2026-07-28")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{
                          "_meta":{
                            "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                            "io.modelcontextprotocol/clientInfo":{"name":"robots-test","version":"1.0"},
                            "io.modelcontextprotocol/clientCapabilities":{}
                          }
                        }}
                        """)
                .when().post("/mcp");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(List.of(), response.headers().getValues("X-Robots-Tag"));
    }

    // Guard: the header changes nothing of the API's cache policy or CORS.
    @Test
    void theApiCachePolicyAndCorsStayTheSame() {
        Response response = given().header("Origin", "https://app.example").when().get("/api/people/1");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(List.of(API_CACHE_CONTROL), response.headers().getValues("Cache-Control"));
        Assertions.assertTrue(response.header("Vary").contains("Origin"), response.header("Vary"));
        Assertions.assertEquals("https://app.example", response.header("Access-Control-Allow-Origin"));

        Response preflight = given()
                .header("Origin", "https://app.example")
                .header("Access-Control-Request-Method", "GET")
                .when().options("/api/people/1");
        Assertions.assertEquals(200, preflight.statusCode());
        Assertions.assertEquals("https://app.example", preflight.header("Access-Control-Allow-Origin"));
        Assertions.assertEquals(List.of(), preflight.headers().getValues("X-Robots-Tag"));
    }
}
