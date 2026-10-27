package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.KnownEntities;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every record address of the six types arrives with its content in the HTML
 * (2.12.0, slice E2), drawn by the one template of detail. Indexing does not
 * change: films index,follow with their own canonical, the other types
 * noindex,follow with the canonical of their list (slice D). The host is
 * preview.example: what comes from the host comes from the request.
 */
@QuarkusTest
class ServerRenderedRecordTest {

    private static final Pattern MAIN = Pattern.compile("(?s)<main id=\"main-content\"[^>]*>.*?</main>");
    private static final Pattern H1 = Pattern.compile("<h1 class=\"detail-title\">(.*?)</h1>");

    @Inject
    KnownEntities known;

    @Test
    void aFilmHasItsAttributesRelationsSamplesAndJson() {
        String body = document("/resource/films/1");
        String main = main(body);
        assertTrue(main.startsWith("<main id=\"main-content\" tabindex=\"-1\" data-ssr-page=\"resource-detail\""
                + " data-ssr-type=\"films\" data-ssr-id=\"1\">"), main);
        assertTrue(main.contains("<h1 class=\"detail-title\">A New Hope</h1>"), main);
        assertTrue(main.contains("<th scope=\"row\">Director</th><td>George Lucas</td>"), main);
        assertTrue(main.contains("href=\"/resource/people/1\">Luke Skywalker</a>"), main);
        assertTrue(main.contains("href=\"/resource/planets/1\">Tatooine</a>"), main);
        assertTrue(main.contains("curl https://preview.example/api/films/1"), main);
        assertTrue(main.contains("fetch(&#39;https://preview.example/api/films/1&#39;)"), main);
        assertTrue(main.contains("requests.get(&#39;https://preview.example/api/films/1&#39;).json()"), main);
        assertTrue(main.contains("claude mcp add --transport http swapi-build https://preview.example/mcp"), main);
        assertTrue(main.contains("{&quot;resource&quot;:&quot;FILMS&quot;,&quot;id&quot;:1}"), main);
        assertTrue(main.contains("GET /api/films/1 <span class=\"status-code\">200</span>"), main);
        assertTrue(main.contains("&quot;https://preview.example/api/films/1&quot;"), "the JSON, escaped: " + main);
        assertFalse(main.contains("https://swapi.build"), main);
        // Guard of slice D: the head of a film is unchanged.
        assertTrue(body.contains("<meta name=\"robots\" content=\"index,follow\" />"), body);
        assertTrue(body.contains("<link rel=\"canonical\" href=\"https://preview.example/resource/films/1\" />"), body);
    }

    @Test
    void theOpeningCrawlIsTextWithItsLineBreaks() {
        String main = main(document("/resource/films/1"));
        Matcher m = Pattern.compile("(?s)<p class=\"record-crawl\">(.*?)</p>").matcher(main);
        assertTrue(m.find(), main);
        assertTrue(m.group(1).startsWith("It is a period of civil war."), m.group(1));
        assertTrue(m.group(1).contains("\r\n"), "the line breaks of the data stay, for white-space: pre-line");
        assertFalse(m.group(1).contains("<"), m.group(1));
    }

    @Test
    void aPersonHasContentAndStaysOutOfTheIndex() {
        String body = document("/resource/people/1");
        String main = main(body);
        assertTrue(main.contains("<h1 class=\"detail-title\">Luke Skywalker</h1>"), main);
        assertTrue(main.contains("href=\"/resource/planets/1\">Tatooine</a>"), main);
        // Guard of the batch rule of slice D: content, but still out of the index.
        assertTrue(body.contains("<meta name=\"robots\" content=\"noindex,follow\" />"), body);
        assertTrue(body.contains("<link rel=\"canonical\" href=\"https://preview.example/resource/people\" />"), body);
        String sitemap = given().when().get("/sitemap.xml").then().statusCode(200).extract().asString();
        assertEquals(20, sitemap.split("<loc>", -1).length - 1);
        assertFalse(sitemap.contains("/resource/people/1<"), sitemap);
    }

    @Test
    void aRelationWithoutRecordsHasNoBlock() {
        String main = main(document("/resource/species/2"));
        assertTrue(main.contains("<h1 class=\"detail-title\">Droid</h1>"), main);
        assertFalse(main.contains(">Homeworld</h3>"), "Droid has no homeworld: " + main);
        assertTrue(main.contains(">People</h3>"), main);
    }

    /** A record added to the data file gets its page with no code change: every id of every type. */
    @Test
    void everyRecordOfEveryTypeIsDrawn() {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String type : List.of("people", "films", "planets", "species", "starships", "vehicles")) {
            List<Integer> ids = known.ids(type);
            assertFalse(ids.isEmpty(), type);
            for (int id : ids) {
                String main = main(document("/resource/" + type + "/" + id));
                String marker = "data-ssr-page=\"resource-detail\" data-ssr-type=\"" + type + "\" data-ssr-id=\"" + id + "\"";
                Matcher h1 = H1.matcher(main);
                String name = known.name(type, Integer.toString(id)).orElseThrow();
                if (!main.contains(marker) || !h1.find() || !h1.group(1).equals(escape(name))) {
                    wrong.add(type + "/" + id);
                }
                checked++;
            }
        }
        assertEquals(List.of(), wrong);
        assertTrue(checked > 200, "checked " + checked);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/resource/people/9999", "/resource/people/%31", "/resource/people/01", "/resource/droids/1"})
    void aRecordThatIsNotThereDrawsNothing(String path) {
        String body = given().accept("text/html").urlEncodingEnabled(false).when().get(path)
                .then().statusCode(404).extract().asString();
        assertTrue(body.contains(ServerRendering.EMPTY_MAIN), path);
        assertFalse(body.contains("data-ssr-"), path);
    }

    @Test
    void theQueryIsNeverReflected() {
        String body = given().accept("text/html").urlEncodingEnabled(false)
                .when().get("/resource/films/1?%3Cscript%3Ex%3C/script%3E")
                .then().statusCode(200).extract().asString();
        assertFalse(body.contains("<script>x"), body);
        assertFalse(body.contains("%3Cscript%3Ex"), body);
        assertFalse(main(body).contains("script"), main(body));
    }

    @Test
    void theHostIsEscaped() {
        String main = main(given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "a&b.example")
                .when().get("/resource/films/1").then().statusCode(200).extract().asString());
        assertTrue(main.contains("curl https://a&amp;b.example/api/films/1"), main);
        assertFalse(main.contains("a&b.example"), main);
    }

    @Test
    void headAnswersWithoutABody() {
        given().accept("text/html").when().head("/resource/films/1").then().statusCode(200);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String document(String path) {
        Response response = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
                .when().get(path);
        response.then().statusCode(200);
        return response.asString();
    }

    private static String main(String body) {
        Matcher m = MAIN.matcher(body);
        assertTrue(m.find(), "no <main> in the document");
        return m.group();
    }
}
