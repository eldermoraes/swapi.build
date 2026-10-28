package com.eldermoraes.seo.md;

import com.eldermoraes.seo.KnownEntities;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

/**
 * /llms-full.txt (2.14.0): the summary of llms.txt, the copies of the fixed
 * pages in order, and an index of every record (its copy and its JSON). text/plain
 * like llms.txt, the SEO cache policy, Vary: Origin and X-Robots-Tag: noindex
 * (Google indexes .txt, and this file duplicates the pages).
 */
@QuarkusTest
class LlmsFullTest {

    private static final List<String> TYPES = List.of("people", "films", "planets", "species", "starships", "vehicles");

    @Inject
    MarkdownCopies copies;

    @Inject
    KnownEntities known;

    private static String full() {
        return given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "preview.example")
                .when().get("/llms-full.txt").then().statusCode(200).extract().body().asString();
    }

    @Test
    void isTextEvenWithAnHtmlAccept() {
        Response response = given().accept("text/html").when().get("/llms-full.txt");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("text/plain; charset=utf-8", response.contentType());
        Assertions.assertFalse(response.body().asString().contains("<html"));
    }

    @Test
    void trailingSlashIsAlso200() {
        given().when().get("/llms-full.txt/").then().statusCode(200);
    }

    @Test
    void startsWithTheLlmsTxtSummary() {
        String body = full();
        Assertions.assertTrue(body.startsWith("# swapi.build\n\n> Free, read-only Star Wars API"), body.substring(0, 200));
        Assertions.assertTrue(body.contains("claude mcp add --transport http swapi-build https://preview.example/mcp"));
        // The summary only: the link sections of llms.txt stay there.
        Assertions.assertFalse(body.contains("## Examples"), "the summary carries no link section");
    }

    // The summary and the index alone pass the size floor: one phrase of each
    // copy that is not in the summary proves the copies are in.
    @Test
    void hasAPhraseOfEveryCopy() {
        String body = full();
        for (String phrase : List.of("The Star Wars API", "/api/people/{id}", "Connect your client", "How does swapi.build compare?",
                "Why build another one?")) {
            Assertions.assertTrue(body.contains(phrase), phrase);
        }
    }

    @Test
    void hasOneHtmlVersionLinePerCopy() {
        long lines = full().lines().filter(line -> line.startsWith("> HTML version:")).count();
        Assertions.assertEquals(5, lines);
    }

    @Test
    void hasEveryCopyInOrder() {
        String body = full();
        int home = body.indexOf("> HTML version: https://preview.example/\n");
        int docs = body.indexOf("> HTML version: https://preview.example/docs\n");
        int mcp = body.indexOf("> HTML version: https://preview.example/docs/mcp\n");
        int faq = body.indexOf("> HTML version: https://preview.example/faq\n");
        int about = body.indexOf("> HTML version: https://preview.example/about\n");
        int records = body.indexOf("\n## Records\n");
        Assertions.assertTrue(0 < home && home < docs && docs < mcp && mcp < faq && faq < about && about < records,
                home + " " + docs + " " + mcp + " " + faq + " " + about + " " + records);
        Assertions.assertTrue(body.substring(0, home).endsWith("---\n\n"), "a separator before each copy");
    }

    @Test
    void recordIndexHasEveryKnownRecord() {
        String body = full();
        String index = body.substring(body.indexOf("\n## Records\n"));
        int expected = 0;
        for (String type : TYPES) {
            int ids = known.ids(type).size();
            expected += ids;
            Matcher m = Pattern.compile("\\(https://preview\\.example/resource/" + type + "/\\d+\\.md\\)").matcher(index);
            int found = 0;
            while (m.find()) {
                found++;
            }
            Assertions.assertEquals(ids, found, type);
        }
        Assertions.assertEquals(expected, index.lines().filter(line -> line.startsWith("- [")).count());
        Assertions.assertTrue(expected > 250, "about 260 records: " + expected);
    }

    @Test
    void recordIndexLinksTheCopyAndTheJson() {
        String body = full();
        Assertions.assertTrue(body.contains("### People\n\n- [Luke Skywalker](https://preview.example/resource/people/1.md)"
                + " — https://preview.example/api/people/1\n"), "Luke");
        Assertions.assertTrue(body.contains("- [A New Hope](https://preview.example/resource/films/1.md)"
                + " — https://preview.example/api/films/1\n"), "A New Hope");
        int luke = body.indexOf("/resource/people/1.md)");
        int c3po = body.indexOf("/resource/people/2.md)");
        Assertions.assertTrue(luke < c3po, "in the order of the ids");
    }

    @Test
    void sizeIsBetween20And100Kilobytes() {
        int bytes = full().getBytes(StandardCharsets.UTF_8).length;
        Assertions.assertTrue(bytes >= 20_000 && bytes <= 100_000, bytes + " bytes");
    }

    @Test
    void hasTheSeoCachePolicyVaryOriginAndNoindex() {
        Response response = given().header("Origin", "https://app.example").when().get("/llms-full.txt");
        Assertions.assertEquals(List.of("public, max-age=300, s-maxage=86400"), response.headers().getValues("Cache-Control"));
        Assertions.assertTrue(response.header("Vary").contains("Origin"), response.header("Vary"));
        Assertions.assertEquals(List.of("noindex"), response.headers().getValues("X-Robots-Tag"));
    }

    @Test
    void usesTheRequestHost() {
        String body = given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "other.example")
                .when().get("/llms-full.txt").then().statusCode(200).extract().body().asString();
        Assertions.assertTrue(body.contains("https://other.example/mcp"));
        Assertions.assertTrue(body.contains("https://other.example/resource/people/1.md"));
        Assertions.assertFalse(body.contains("preview.example"));
    }

    @Test
    void isBuiltOncePerSiteRoot() {
        String first = copies.llmsFull("https://once.example").orElseThrow();
        String second = copies.llmsFull("https://once.example").orElseThrow();
        Assertions.assertSame(first, second);
    }

    @Test
    void cacheHoldsAtMostFourRoots() {
        Assertions.assertEquals(4, MarkdownCopies.ROOTS);
        for (int i = 0; i < 6; i++) {
            copies.llmsFull("https://root" + i + ".example").orElseThrow();
        }
        Assertions.assertTrue(copies.cachedRoots() <= MarkdownCopies.ROOTS, "roots in the cache: " + copies.cachedRoots());
        // Least recently used goes first: the two oldest roots left, the four newest stayed.
        Assertions.assertFalse(copies.isCached("https://root0.example", MarkdownCopies.LLMS_FULL));
        Assertions.assertFalse(copies.isCached("https://root1.example", MarkdownCopies.LLMS_FULL));
        for (int i = 2; i < 6; i++) {
            Assertions.assertTrue(copies.isCached("https://root" + i + ".example", MarkdownCopies.LLMS_FULL), "root" + i);
        }
    }
}
