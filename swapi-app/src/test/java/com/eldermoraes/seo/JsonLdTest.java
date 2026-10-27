package com.eldermoraes.seo;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lean JSON-LD of slice E1 (2.11.0). The script is always read with JSON-P,
 * which proves it is valid JSON. @id values are relative to the root of the
 * request host (preview.example here).
 */
@QuarkusTest
class JsonLdTest {

    private static final Pattern SCRIPT = Pattern.compile("(?s)<script type=\"application/ld\\+json\">(.*?)</script>");

    @Test
    void theHomeDescribesTheSiteItsAuthorAndTheDataset() {
        JsonObject home = graph("/");
        String homeDescription = home().description();

        JsonObject site = node(home, "WebSite").orElseThrow();
        assertEquals("https://preview.example/#website", site.getString("@id"));
        assertEquals(List.of("swapi build", "swapi.build Star Wars API"), strings(site.getJsonArray("alternateName")));
        assertEquals(homeDescription, site.getString("description"));
        assertEquals("https://preview.example/#author", site.getJsonObject("author").getString("@id"));

        JsonObject person = node(home, "Person").orElseThrow();
        assertEquals("https://preview.example/#author", person.getString("@id"));
        assertEquals("Elder Moraes", person.getString("name"));
        assertEquals("https://www.linkedin.com/in/eldermoraes/", person.getString("url"));
        assertEquals(List.of("https://github.com/eldermoraes"), strings(person.getJsonArray("sameAs")));

        JsonObject app = node(home, "SoftwareApplication").orElseThrow();
        assertEquals("https://preview.example/#author", app.getJsonObject("author").getString("@id"));

        JsonObject dataset = node(home, "Dataset").orElseThrow();
        assertEquals("https://swapi.dev", dataset.getString("isBasedOn"));
        assertTrue(dataset.getString("description").length() > 50);
        assertFalse(dataset.containsKey("license"), "the data is not Apache 2.0: no license");
        assertTrue(dataset.getBoolean("isAccessibleForFree"));
        JsonObject creator = dataset.getJsonObject("creator");
        assertEquals("Person", creator.getString("@type"));
        assertEquals("Elder Moraes", creator.getString("name"));
        JsonArray distribution = dataset.getJsonArray("distribution");
        assertEquals(6, distribution.size());
        for (String type : List.of("people", "films", "planets", "species", "starships", "vehicles")) {
            assertTrue(distribution.stream().map(JsonValue::asJsonObject)
                    .anyMatch(d -> d.getString("contentUrl").equals("https://preview.example/api/" + type)
                            && d.getString("encodingFormat").equals("application/json")
                            && d.getString("@type").equals("DataDownload")), type);
        }
        assertTrue(node(home, "BreadcrumbList").isEmpty(), "the home is not a crumb");
    }

    @Test
    void theMcpGuideHasAThreeStepBreadcrumb() {
        JsonObject list = node(graph("/docs/mcp"), "BreadcrumbList").orElseThrow();
        assertEquals(List.of(
                        crumb(1, "Home", "https://preview.example/"),
                        crumb(2, "Documentation", "https://preview.example/docs"),
                        crumb(3, "MCP server", "https://preview.example/docs/mcp")),
                list.getJsonArray("itemListElement").stream().map(JsonValue::asJsonObject).toList());
        assertTrue(node(graph("/docs/mcp"), "Dataset").isEmpty());
    }

    @Test
    void aListingHasTwoCrumbs() {
        JsonObject list = node(graph("/resource/films"), "BreadcrumbList").orElseThrow();
        assertEquals(List.of(crumb(1, "Home", "https://preview.example/"),
                        crumb(2, "Films", "https://preview.example/resource/films")),
                list.getJsonArray("itemListElement").stream().map(JsonValue::asJsonObject).toList());
    }

    // Changes on purpose in 2.12.0 (slice E2): a record address had no crumbs in
    // 2.11.0. Home, the list of its type, the record; the noindex of the other
    // types does not stop the markup. Still no Dataset.
    @Test
    void aRecordAddressHasThreeCrumbsAndNoDataset() {
        JsonObject film = graph("/resource/films/1");
        assertEquals(List.of(crumb(1, "Home", "https://preview.example/"),
                        crumb(2, "Films", "https://preview.example/resource/films"),
                        crumb(3, "A New Hope", "https://preview.example/resource/films/1")),
                node(film, "BreadcrumbList").orElseThrow().getJsonArray("itemListElement").stream()
                        .map(JsonValue::asJsonObject).toList());
        assertTrue(node(film, "Dataset").isEmpty());

        JsonObject person = graph("/resource/people/1");
        assertEquals(List.of(crumb(1, "Home", "https://preview.example/"),
                        crumb(2, "People", "https://preview.example/resource/people"),
                        crumb(3, "Luke Skywalker", "https://preview.example/resource/people/1")),
                node(person, "BreadcrumbList").orElseThrow().getJsonArray("itemListElement").stream()
                        .map(JsonValue::asJsonObject).toList());
        assertTrue(node(person, "Dataset").isEmpty());
    }

    // Changes on purpose from 2.10.0, which used the description of the route.
    @Test
    void theWebSiteAlwaysCarriesTheHomeDescription() {
        assertEquals(home().description(), node(graph("/docs"), "WebSite").orElseThrow().getString("description"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/docs/mcp", "/about", "/resource/films", "/resource/films/1"})
    void theScriptNeverHoldsACloseTagOrACommentOpener(String path) {
        String script = script(path);
        assertFalse(script.contains("</"), script);
        assertFalse(script.contains("<!--"), script);
    }

    @Test
    void theEscapeKeepsAHostileLabelInsideTheScript() {
        String json = Json.createObjectBuilder().add("name", "</script><!--x").build().toString();
        String escaped = JsonLd.forScript(json);
        assertFalse(escaped.contains("</"), escaped);
        assertFalse(escaped.contains("<!--"), escaped);
        try (JsonReader reader = Json.createReader(new StringReader(escaped))) {
            assertEquals("</script><!--x", reader.readObject().getString("name"));
        }
    }

    @Test
    void theNotFoundPageHasNoScript() {
        String body = given().accept("text/html").when().get("/not-a-real-page").then().statusCode(404).extract().asString();
        assertFalse(body.contains("application/ld+json"));
    }

    private static SeoMetadata home() {
        return io.quarkus.arc.Arc.container().instance(SeoMetadataRegistry.class).get().home();
    }

    private static JsonObject crumb(int position, String name, String item) {
        return Json.createObjectBuilder().add("@type", "ListItem").add("position", position).add("name", name)
                .add("item", item).build();
    }

    private static Optional<JsonObject> node(JsonObject graph, String type) {
        return graph.getJsonArray("@graph").stream().map(JsonValue::asJsonObject)
                .filter(n -> type.equals(n.getString("@type"))).findFirst();
    }

    private static List<String> strings(JsonArray array) {
        return array.getValuesAs(jakarta.json.JsonString.class).stream().map(jakarta.json.JsonString::getString).toList();
    }

    private static JsonObject graph(String path) {
        try (JsonReader reader = Json.createReader(new StringReader(script(path)))) {
            JsonObject graph = reader.readObject();
            assertEquals("https://schema.org", graph.getString("@context"));
            return graph;
        }
    }

    private static String script(String path) {
        String body = given().accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
                .when().get(path).then().statusCode(200).extract().asString();
        Matcher m = SCRIPT.matcher(body);
        assertTrue(m.find(), "no JSON-LD on " + path);
        return m.group(1);
    }
}
