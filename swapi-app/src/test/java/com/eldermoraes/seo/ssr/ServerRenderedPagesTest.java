package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.FaqFile;
import com.eldermoraes.seo.ForbiddenClaims;
import com.eldermoraes.seo.KnownEntities;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
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
        // 2.13.0 (slice F1): the new arguments, the resources and the server prompt.
        assertTrue(main.contains("<code>expand</code>"), main);
        assertTrue(main.contains("<code>fields</code>"), main);
        assertTrue(main.contains("swapi://people/1"), main);
        assertTrue(main.contains("character_profile"), main);
        // Claude Code splits slash-command arguments on whitespace: a one-word
        // example, and the note that a full name needs a client sending JSON.
        assertTrue(main.contains("<code>/mcp__swapi-build__character_profile Vader</code>"), main);
        assertFalse(main.contains("character_profile Darth Vader"), main);
        assertTrue(main.contains("arguments as JSON"), main);
        assertTrue(main.contains("Who is Luke Skywalker? Include his homeworld and species."), main);
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

    // The host goes into the <main> only on /docs/mcp (and, since 2.12.0, on the
    // record addresses, ServerRenderedRecordTest). Changes on purpose in 2.12.0
    // (review): until then a host such as "a&b.example" went in escaped; now it
    // is not a host (PublicSiteUrlResolver), so it never goes in, escaped or not,
    // and the request's own host is used (HostileForwardedHostTest).
    @Test
    void aHostWithAnAmpersandNeverReachesTheMain() {
        String body = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "a&b.example")
                .when().get("/docs/mcp").then().statusCode(200).extract().asString();
        String main = first(MAIN, body);
        assertTrue(main.contains("https://localhost"), main);
        assertFalse(main.contains("b.example"), main);
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
    // /resource/films/1 left this list on purpose in 2.12.0 (ServerRenderedRecordTest).
    @ParameterizedTest
    @ValueSource(strings = {"/docs/webmcp", "/stats", "/privacy", "/terms", "/resource/films"})
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

    // 2.15.0 (slice G2): /faq, drawn from seo/faq.json.
    private static final FaqFile FAQ = FaqFile.load();

    @Inject
    KnownEntities known;

    @Test
    void faqHasTheQuestionsAndTheComparison() {
        String main = main("/faq");
        assertTrue(main.startsWith("<main id=\"main-content\" tabindex=\"-1\" data-ssr-page=\"faq\">"), main);
        for (FaqFile.Question q : FAQ.questions()) {
            assertTrue(main.contains("<section class=\"about-section faq-question\" id=\"" + q.id() + "\">"), q.id());
            assertTrue(main.contains("<h2>" + escaped(q.question()) + "</h2>"), q.question());
        }
        assertTrue(main.contains("<section class=\"about-section faq-compare\" id=\"compare\">"), main);
        assertTrue(main.contains("<h2>How does swapi.build compare?</h2>"), main);
        for (String column : FAQ.comparison().columns()) {
            assertTrue(main.contains("<th scope=\"col\">" + escaped(column) + "</th>"), column);
        }
        for (FaqFile.Row row : FAQ.comparison().rows()) {
            assertTrue(main.contains("<tr><th scope=\"row\">" + escaped(row.label()) + "</th>"), row.label());
        }
        assertFalse(main.contains("preview.example"), "nothing of the host on the FAQ");
        assertFalse(main.contains("<script"), "no JavaScript on the FAQ");
    }

    @Test
    void faqCountsComeFromTheData() {
        String main = main("/faq");
        assertFalse(main.contains("{count:"), main);
        for (String type : new String[]{"films", "people", "planets", "species", "starships", "vehicles"}) {
            int count = known.ids(type).size();
            assertTrue(count > 0, type);
            assertTrue(main.contains(count + " " + type), count + " " + type);
        }
        assertTrue(main.contains("<td>Episodes I-VI: " + known.ids("films").size() + " films, "
                + known.ids("people").size() + " people</td>"), main);
    }

    @Test
    void faqCreditsTheOriginalSwapi() {
        String main = main("/faq");
        assertTrue(main.contains("Paul Hallett"), main);
        assertTrue(main.contains("Juriy Bura"), main);
        assertTrue(main.contains("<a href=\"https://swapi.dev\" target=\"_blank\" rel=\"noopener\">The original SWAPI</a>"), main);
    }

    @Test
    void faqShowsTheCheckedOnDate() {
        String main = main("/faq");
        String day = FAQ.checkedOn().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH));
        assertTrue(main.contains("Checked on <time datetime=\"" + FAQ.checkedOn() + "\">" + day + "</time>."), main);
    }

    @Test
    void faqSourcesAreLinks() {
        String main = main("/faq");
        String notes = main.substring(main.indexOf("<ul class=\"faq-sources\">"));
        for (FaqFile.Row row : FAQ.comparison().rows()) {
            for (int i = 0; i < row.cells().size(); i++) {
                String link = "<a href=\"" + escaped(row.cells().get(i).source()) + "\" target=\"_blank\" rel=\"noopener\">"
                        + escaped(FAQ.comparison().columns().get(i)) + "</a>";
                assertTrue(notes.contains(link), link);
            }
        }
    }

    // Availability is never a number in the table (plan of slice G, decision 2):
    // the row says whether each API has a public status page, and the one number,
    // swapi.build's own, is in the prose of one answer, with its method.
    @Test
    void faqAvailabilityIsAStatusPageRowNotANumber() {
        String main = main("/faq");
        String table = main.substring(main.indexOf("<table"), main.indexOf("</table>"));
        assertTrue(table.contains("<th scope=\"row\">Public status page</th><td>None; public monitor history</td>"), table);
        assertFalse(table.contains("%"), table);
        assertFalse(table.toLowerCase(java.util.Locale.ROOT).contains("uptime"), table);
        assertFalse(table.contains("checks"), table);
        String answer = main.substring(main.indexOf("id=\"is-it-up\""), main.indexOf("id=\"use-the-code\""));
        assertTrue(answer.matches("(?s).*\\d+ of \\d+ scheduled checks of GET /api/people/1 passed between .*"), answer);
        assertTrue(answer.contains("best-effort"), answer);
        assertTrue(answer.contains("href=\"https://github.com/eldermoraes/swapi.build/actions/workflows/uptime.yml\""), answer);
    }

    // No page drawn on the server, and no template it is drawn from, promises
    // uptime or claims to be first, alone or unique; the FAQ, which talks about
    // other APIs, also says nothing about them being down (ForbiddenClaims).
    @Test
    void noPageMakesAForbiddenClaim() throws java.io.IOException {
        for (String path : new String[]{"/", "/docs", "/docs/mcp", "/faq", "/about", "/resource/films/1", "/resource/people/1"}) {
            assertEquals(java.util.List.of(), ForbiddenClaims.everywhere(main(path)), path);
        }
        assertEquals(java.util.List.of(), ForbiddenClaims.aboutThirdParties(main("/faq").replaceAll("<[^>]*>", " ")), "/faq");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/resources/templates/ssr"))) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                assertEquals(java.util.List.of(), ForbiddenClaims.everywhere(java.nio.file.Files.readString(file)), file.toString());
            }
        }
    }

    /** The text as the default escaping of a .html template writes it. */
    private static String escaped(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
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
