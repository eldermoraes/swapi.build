package com.eldermoraes.seo.md;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * The Markdown copy of every page whose {@code <main>} the server draws (2.14.0):
 * the page's address plus .md (the home is /index.html.md), text/markdown, the
 * same indexing as the HTML of the page, never chosen by the Accept header.
 */
@QuarkusTest
class MarkdownCopiesTest {

    private static final String SEO_CACHE_CONTROL = "public, max-age=300, s-maxage=86400";
    private static final String MARKDOWN = "text/markdown; charset=utf-8";

    private static RequestSpecification preview() {
        return given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "preview.example")
                .urlEncodingEnabled(false).redirects().follow(false);
    }

    private static String copy(String path) {
        return preview().when().get(path).then().statusCode(200).extract().body().asString();
    }

    // The phrase of each ssr_probe of verify-deploy.sh.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "/index.html.md | # The Star Wars API",
            "/docs.md | /api/people/{id}",
            "/docs/mcp.md | claude mcp add --transport http swapi-build https://preview.example/mcp",
            "/about.md | ## Why build another one?",
    })
    void fixedPagesHaveACopy(String path, String phrase) {
        String body = copy(path);
        Assertions.assertTrue(body.contains(phrase), path + ": " + body);
    }

    @Test
    void homeCopyIsIndexHtmlMd() {
        String body = copy("/index.html.md");
        Assertions.assertTrue(body.startsWith("> HTML version: https://preview.example/\n\n"), body);
        Assertions.assertTrue(body.contains("[MCP for agents](https://preview.example/docs/mcp.md)"), body);
    }

    @Test
    void recordCopyHasNameAttributesRelationsAndJson() {
        String film = copy("/resource/films/1.md");
        for (String want : List.of("# A New Hope", "| Director | George Lucas |", "### Characters",
                "```bash\ncurl https://preview.example/api/films/1\n```", "```json\n{", "\"title\": \"A New Hope\"")) {
            Assertions.assertTrue(film.contains(want), want + " in " + film);
        }
        String luke = copy("/resource/people/1.md");
        for (String want : List.of("# Luke Skywalker", "| Height | 172 |", "| Birth year | 19BBY |",
                "[Tatooine](https://preview.example/resource/planets/1.md)","\"name\": \"Luke Skywalker\"")) {
            Assertions.assertTrue(luke.contains(want), want + " in " + luke);
        }
    }

    @Test
    void recordRelationsLinkToTheirCopies() {
        String film = copy("/resource/films/1.md");
        Assertions.assertTrue(film.contains("[Luke Skywalker](https://preview.example/resource/people/1.md)"), film);
        Assertions.assertTrue(film.contains("[← Films](https://preview.example/resource/films)"), film);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/index.html.md | https://preview.example/",
            "/docs.md | https://preview.example/docs",
            "/docs/mcp.md | https://preview.example/docs/mcp",
            "/about.md | https://preview.example/about",
            "/resource/films/1.md | https://preview.example/resource/films/1",
            "/resource/people/1.md | https://preview.example/resource/people/1",
    })
    void copyStartsWithTheHtmlVersionLine(String path, String html) {
        Assertions.assertTrue(copy(path).startsWith("> HTML version: " + html + "\n\n"), path);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/index.html.md | The Star Wars API",
            "/docs.md | /api/people/{id}",
            "/docs/mcp.md | Connect your client",
            "/about.md | Why build another one?",
            "/resource/films/1.md | George Lucas",
    })
    void copyHasNoShellNoMenuNoFooter(String path, String phrase) {
        String body = copy(path);
        Assertions.assertTrue(body.contains(phrase), path);
        for (String shell : List.of("<main", "<html", "<script", "Privacy Policy", "GALACTIC TERMINAL", "data-ssr-",
                "API endpoint path", "Copy</", "EXEC")) {
            Assertions.assertFalse(body.contains(shell), path + " carries " + shell);
        }
    }

    @Test
    void aboutCopyHasNoDecorativeIcons() {
        String body = copy("/about.md");
        Assertions.assertTrue(body.contains("### Quarkus Performance"), body);
        Assertions.assertFalse(body.contains("⚡"), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.html.md", "/docs.md", "/docs/mcp.md", "/about.md", "/resource/films/1.md",
            "/resource/people/1.md"})
    void copyContentTypeIsTextMarkdownUtf8(String path) {
        Response response = preview().accept("text/html").when().get(path);
        Assertions.assertEquals(200, response.statusCode(), path);
        Assertions.assertEquals(MARKDOWN, response.contentType(), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.html.md", "/docs.md", "/docs/mcp.md", "/about.md", "/resource/films/1.md"})
    void copyHasTheSeoCachePolicyAndVaryOrigin(String path) {
        Response response = preview().header("Origin", "https://app.example").when().get(path);
        Assertions.assertEquals(List.of(SEO_CACHE_CONTROL), response.headers().getValues("Cache-Control"), path);
        Assertions.assertTrue(response.header("Vary").contains("Origin"), path + ": " + response.header("Vary"));
    }

    // The spa filter (application.properties) matches /resource/[^/]+/[^/]+, so
    // /resource/films/1.md too: its HTML policy must never stay on the copy.
    @ParameterizedTest
    @ValueSource(strings = {"/resource/films/1.md", "/resource/people/1.md"})
    void recordCopyHasOneCacheControlTheMarkdownOne(String path) {
        for (Response response : List.of(preview().when().get(path), preview().when().head(path))) {
            Assertions.assertEquals(List.of(SEO_CACHE_CONTROL), response.headers().getValues("Cache-Control"), path);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/index.html.md | https://preview.example/",
            "/docs.md | https://preview.example/docs",
            "/docs/mcp.md | https://preview.example/docs/mcp",
            "/about.md | https://preview.example/about",
            "/resource/films/1.md | https://preview.example/resource/films/1",
    })
    void indexableCopiesLinkTheirOwnHtmlAsCanonical(String path, String canonical) {
        Response response = preview().when().get(path);
        Assertions.assertEquals(List.of("<" + canonical + ">; rel=\"canonical\""), response.headers().getValues("Link"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.html.md", "/docs.md", "/docs/mcp.md", "/about.md", "/resource/films/1.md"})
    void indexableCopiesHaveNoRobotsHeader(String path) {
        Response response = preview().when().get(path);
        Assertions.assertEquals(200, response.statusCode(), path);
        Assertions.assertEquals(List.of(), response.headers().getValues("X-Robots-Tag"), path);
    }

    // The HTML of a people record is noindex,follow with the list as canonical
    // (seo/site.json, indexable false); its copy says the same in headers.
    @ParameterizedTest
    @CsvSource({"/resource/people/1.md, /resource/people", "/resource/planets/1.md, /resource/planets",
            "/resource/vehicles/4.md, /resource/vehicles"})
    void nonIndexableRecordCopyIsNoindexWithTheListAsCanonical(String path, String list) {
        Response response = preview().when().get(path);
        Assertions.assertEquals(200, response.statusCode(), path);
        Assertions.assertEquals(List.of("noindex"), response.headers().getValues("X-Robots-Tag"), path);
        Assertions.assertEquals(List.of("<https://preview.example" + list + ">; rel=\"canonical\""),
                response.headers().getValues("Link"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/docs.md", "/resource/people/1.md"})
    void headAnswersTheSameHeadersAsGet(String path) {
        Response get = preview().when().get(path);
        Response head = preview().when().head(path);
        Assertions.assertEquals(200, head.statusCode(), path);
        for (String name : List.of("Content-Type", "Cache-Control", "Link", "X-Robots-Tag")) {
            Assertions.assertEquals(get.headers().getValues(name), head.headers().getValues(name), path + " " + name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/docs/webmcp.md", "/stats.md", "/privacy.md", "/terms.md", "/resource/people.md"})
    void pagesWithoutServerContentHaveNoCopy(String path) {
        Response response = preview().accept("text/html").when().get(path);
        Assertions.assertEquals(404, response.statusCode(), path);
        Assertions.assertFalse(String.valueOf(response.contentType()).startsWith("text/markdown"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/resource/people/9999.md", "/resource/people/01.md", "/resource/droids/1.md"})
    void unknownOrNonCanonicalRecordCopyIs404(String path) {
        Response response = preview().accept("text/html").when().get(path);
        Assertions.assertEquals(404, response.statusCode(), path);
        Assertions.assertEquals(List.of("no-cache"), response.headers().getValues("Cache-Control"), path);
        Assertions.assertFalse(response.body().asString().contains("Luke Skywalker"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/.md", "/docs/.md", "/index.md", "/index.html.md/", "/docs.md/", "/DOCS.MD", "/docs%2Emd",
            "/foo.md", "/docs.md.md", "/resource/films/1.md/"})
    void ambiguousSuffixesAre404(String path) {
        Response response = preview().accept("text/html").when().get(path);
        Assertions.assertEquals(404, response.statusCode(), path);
        Assertions.assertFalse(String.valueOf(response.contentType()).startsWith("text/markdown"), path);
    }

    @Test
    void queryDoesNotMatter() {
        Response response = preview().when().get("/resource/films/1.md?x=1");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(MARKDOWN, response.contentType());
        Assertions.assertTrue(response.body().asString().contains("George Lucas"));
    }

    // The backend comes first in the router: an /api path never turns into a copy.
    @Test
    void apiPathWithMdIsUntouched() {
        Response response = preview().when().get("/api/people/1.md");
        Assertions.assertEquals(404, response.statusCode());
        Assertions.assertFalse(String.valueOf(response.contentType()).startsWith("text/markdown"));
    }

    // Never by the Accept header (SeoRoutes.route; study, line 101).
    @ParameterizedTest
    @ValueSource(strings = {"/docs", "/resource/films/1"})
    void markdownAcceptStillGetsHtml(String path) {
        preview().accept("text/markdown").when().get(path).then()
                .statusCode(200)
                .contentType(org.hamcrest.Matchers.startsWith("text/html"));
    }

    @Test
    void mcpCopyCarriesTheRequestHost() {
        String other = given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "other.example")
                .when().get("/docs/mcp.md").then().statusCode(200).extract().body().asString();
        Assertions.assertTrue(other.contains("claude mcp add --transport http swapi-build https://other.example/mcp"), other);
        Assertions.assertFalse(other.contains("preview.example"), other);
        Assertions.assertTrue(copy("/docs/mcp.md").contains("https://preview.example/mcp"));
    }
}
