package com.eldermoraes.seo.ssr;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four pages of slice E1 (2.11.0) arrive with their content in the HTML,
 * drawn on the server from the templates, for whoever does not run JavaScript.
 * The host is preview.example: whatever comes from the host must come from the
 * request, never from swapi.build.
 */
@QuarkusTest
class ServerRenderedPagesTest {

    private static final Pattern MAIN = Pattern.compile("(?s)<main id=\"main-content\"[^>]*>.*?</main>");

    @Test
    void theHomeHasItsContent() {
        String main = main("/");
        assertTrue(main.startsWith("<main id=\"main-content\" tabindex=\"-1\" data-ssr-page=\"home\">"), main);
        assertTrue(main.contains("<h1>The Star Wars API</h1>"), main);
        for (String type : new String[]{"people", "films", "planets", "species", "starships", "vehicles"}) {
            assertTrue(main.contains("href=\"/resource/" + type + "\""), type);
        }
        assertTrue(main.contains("data-term=\"home\""), main);
        for (String chip : new String[]{"people/1", "planets/1", "starships/9", "films/1"}) {
            assertTrue(main.contains("data-path=\"" + chip + "\""), chip);
        }
        assertTrue(main.contains("<p class=\"sw-live\"></p>"), "the live line is there and empty");
        assertFalse(main.contains("preview.example"), "nothing of the host on the home");
    }

    @Test
    void theDocsHaveEveryGetOperationOfTheContract() {
        String main = main("/docs");
        assertTrue(main.contains("data-ssr-page=\"docs\""), main);
        JsonObject spec = read(given().when().get("/openapi.json").then().extract().asString());
        int operations = 0;
        for (Map.Entry<String, JsonValue> path : spec.getJsonObject("paths").entrySet()) {
            if (path.getValue().asJsonObject().containsKey("get")) {
                operations++;
                assertTrue(main.contains("data-path=\"" + path.getKey() + "\""), path.getKey());
            }
        }
        assertTrue(main.indexOf("<h2>Root</h2>") < main.indexOf("<h2>People</h2>"), "Root comes before People");
        assertTrue(main.contains("/api/people?search=luke"), main);
        assertTrue(main.contains("People fields"), main);
        assertEquals(operations, count(main, "<form class=\"try-form\">"));
        assertEquals(operations, count(main, "<button type=\"button\" class=\"sw-pill sw-pill--solid sw-pill--sm try-button\">Try it</button>"));
        assertFalse(main.contains("type=\"submit\""), "a Try it button without JavaScript must not submit to /docs?...");
        assertFalse(main.contains("Loading API specification"), main);
    }

    @Test
    void theMcpGuideUsesTheHostOfTheRequest() {
        String main = main("/docs/mcp");
        assertTrue(main.contains("data-ssr-page=\"mcp\""), main);
        assertTrue(main.contains("claude mcp add --transport http swapi-build https://preview.example/mcp"), main);
        assertEquals(5, count(main, "role=\"tab\""));
        assertEquals(5, count(main, "role=\"tabpanel\""));
        assertEquals(4, count(main, "data-inactive"));
        Matcher panel = Pattern.compile("<div class=\"tab-panel\"[^>]*>").matcher(main);
        while (panel.find()) {
            assertFalse(panel.group().contains("hidden"), "a panel hidden without JavaScript: " + panel.group());
        }
        for (String tool : new String[]{"sw_list", "sw_get", "sw_random", "sw_search"}) {
            assertTrue(main.contains("<code>" + tool + "</code>"), tool);
        }
        assertTrue(main.contains("codex mcp add swapi-build --url https://preview.example/mcp"), "the Codex guide, an inactive panel");
        assertFalse(main.contains("https://swapi.build"), "no fixed domain");
        // Decision 1 (Elder, 27/10/2026): texts of the Vercel days.
        assertFalse(main.contains("scales to zero"), main);
        assertFalse(main.contains("different instance"), main);
    }

    @Test
    void theAboutPageHasItsText() {
        String main = main("/about");
        assertTrue(main.contains("data-ssr-page=\"about\""), main);
        assertTrue(main.contains("Why build another one?"), main);
        assertTrue(main.contains("Paul Hallett"), main);
    }

    // The host goes into the <main> only on /docs/mcp, always as escaped text.
    @Test
    void theHostIsEscaped() {
        String body = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "a&b.example")
                .when().get("/docs/mcp").then().statusCode(200).extract().asString();
        String main = first(MAIN, body);
        assertTrue(main.contains("https://a&amp;b.example/mcp"), main);
        assertFalse(main.contains("a&b.example/mcp"), main);
    }

    @Test
    void theQueryIsNeverReflected() {
        String body = given().accept("text/html")
                .urlEncodingEnabled(false)
                .when().get("/docs/mcp?%3Cscript%3Ex%3C/script%3E").then().statusCode(200).extract().asString();
        assertFalse(body.contains("<script>x"), "the query was reflected");
        assertFalse(body.contains("%3Cscript%3Ex"), "the query was reflected");
    }

    @Test
    void headAnswersWithoutABody() {
        given().accept("text/html").when().head("/docs/mcp").then().statusCode(200);
    }

    // Guard: the pages still drawn by the client leave with the empty <main>.
    @ParameterizedTest
    @ValueSource(strings = {"/docs/webmcp", "/stats", "/privacy", "/terms", "/resource/films", "/resource/films/1"})
    void theOtherPagesStayClientRendered(String path) {
        String body = given().accept("text/html").when().get(path).then().statusCode(200).extract().asString();
        assertTrue(body.contains(ServerRendering.EMPTY_MAIN), path);
        assertFalse(body.contains("data-ssr-"), path);
    }

    @Test
    void theNotFoundPageDrawsNothing() {
        String body = given().accept("text/html").when().get("/not-a-real-page").then().statusCode(404).extract().asString();
        assertTrue(body.contains(ServerRendering.EMPTY_MAIN));
        assertFalse(body.contains("data-ssr-"));
    }

    private static String main(String path) {
        Response response = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
                .when().get(path);
        response.then().statusCode(200);
        return first(MAIN, response.asString());
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "no <main> in the document");
        return m.group();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static JsonObject read(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
