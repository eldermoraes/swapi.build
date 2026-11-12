package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteNormalizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api, /api",
            "/api/, /api",
            "/api/stats, /api/stats",
            "/api/people, /api/people",
            "/api/people/, /api/people",
            "/api/people?search=luke, /api/people",
            "/api/people/1, /api/people/{id}",
            "/api/people/1/, /api/people/{id}",
            "/api/films/999999, /api/films/{id}",
            "/api/starships/abc, /api/starships/{id}",
            "/api/vehicles/random, /api/vehicles/random",
            "/mcp, /mcp",
            "/mcp/, /mcp",
            "/openapi.json, /openapi.json",
            "/robots.txt, /robots.txt",
            "/sitemap.xml, /sitemap.xml",
            "/llms.txt, /llms.txt",
            "/llms.txt/, /llms.txt",
            "/, /",
            "/docs, /docs",
            "/docs/mcp, /docs/mcp",
            "/docs/webmcp, /docs/webmcp",
            "/faq, /faq",
            "/about, /about",
            "/privacy, /privacy",
            "/terms, /terms",
            "/stats, /stats",
            "/resource/planets, /resource/planets",
            "/resource/planets/3, /resource/planets/{id}",
            // 2.14.0 (slice G1): the Markdown copies and /llms-full.txt.
            "/llms-full.txt, /llms-full.txt",
            "/llms-full.txt/, /llms-full.txt",
            "/index.html.md, /index.html.md",
            "/docs.md, /docs.md",
            "/docs/mcp.md, /docs/mcp.md",
            "/faq.md, /faq.md",
            "/about.md, /about.md",
            "/resource/people/1.md, /resource/people/{id}.md",
            "/resource/films/1.md, /resource/films/{id}.md",
            "/resource/planets/9999.md, /resource/planets/{id}.md",
            "/resource/species/3.md, /resource/species/{id}.md",
            "/resource/starships/2.md, /resource/starships/{id}.md",
            "/resource/vehicles/4.md, /resource/vehicles/{id}.md",
    })
    void knownRoutesMapToTheirTemplate(String path, String expected) {
        assertEquals(Optional.of(expected), RouteNormalizer.normalize(path));
    }

    // Regression (2.14.0, before the copies shipped): any fourth segment counted
    // as a record page, so /resource/people/1.md would grow /resource/people/{id}.
    @Test
    void recordCopyIsNotCountedAsARecordPage() {
        assertEquals(Optional.of("/resource/people/{id}.md"), RouteNormalizer.normalize("/resource/people/1.md"));
        assertEquals(Optional.of("/resource/people/{id}"), RouteNormalizer.normalize("/resource/people/1"));
        // Only the record address has a copy: the API keeps its own templates.
        assertEquals(Optional.of("/api/people/{id}"), RouteNormalizer.normalize("/api/people/1.md"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/wp-login.php", "/.env", "/api/users", "/api/people/1/films", "/mcp/sse",
            "/mcp/messages/abc", "/resource/droids/1", "/resource/people/1/extra", "/API/people",
            "/docs/other", "/api/people//", "/foo.md", "/stats.md", "/docs/webmcp.md", "/resource/people.md",
            "/index.md", "/resource/droids/1.md",
    })
    void unknownPathsCollapseIntoOther(String path) {
        assertEquals(Optional.of(RouteNormalizer.OTHER), RouteNormalizer.normalize(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/index-abc123.js", "/assets/style.css", "/favicon.svg", "/og-image.png"})
    void staticFilesAreNotCounted(String path) {
        assertEquals(Optional.empty(), RouteNormalizer.normalize(path));
    }

    @Test
    void emptyOrMissingPathIsOther() {
        assertEquals(Optional.of(RouteNormalizer.OTHER), RouteNormalizer.normalize(null));
        assertEquals(Optional.of(RouteNormalizer.OTHER), RouteNormalizer.normalize(""));
    }

    @Test
    void idsNeverReachTheTemplate() {
        // A path segment can carry anything a client typed; it must never become a key.
        assertEquals(Optional.of("/api/people/{id}"), RouteNormalizer.normalize("/api/people/someone@example.com"));
    }

    /** Where the number of templates is written out; a change to the set has to reach all of them. */
    private static final String NOTES = "update RouteNormalizer.TEMPLATE_COUNT and the sizing notes that cite it: "
            + "the class comment of RouteStats, the comment on SnapshotFile.MAX_BYTES and the \"Usage counters\" "
            + "and \"Server times\" paragraphs of docs/DEPLOY.md";

    // #60: the sizing notes said 45 templates when the normalizer produced 59.
    @Test
    void theClosedSetIsExactlyWhatPathsNormalizeTo() {
        // Every segment a template is made of, with something in the place of an
        // id, and segments no template has.
        Set<String> segments = new TreeSet<>(List.of("1", "1.md", "x", "x.md", ".md", "random", "API", ""));
        for (String template : RouteNormalizer.templates()) {
            for (String segment : template.split("/")) {
                segments.add(segment.replace("{id}", "1"));
            }
        }
        List<String> paths = new ArrayList<>(List.of("/"));
        for (String a : segments) {
            paths.add("/" + a);
            for (String b : segments) {
                paths.add("/" + a + "/" + b);
                for (String c : segments) {
                    paths.add("/" + a + "/" + b + "/" + c);
                }
            }
        }

        Set<String> produced = new TreeSet<>();
        for (String path : paths) {
            RouteNormalizer.normalize(path).ifPresent(produced::add);
        }

        assertEquals(new TreeSet<>(RouteNormalizer.templates()), produced,
                "the templates listed and the templates produced differ; " + NOTES);
    }

    @Test
    void everyListedTemplateIsItsOwnTemplate() {
        for (String template : RouteNormalizer.templates()) {
            assertEquals(Optional.of(template), RouteNormalizer.normalize(template));
        }
    }

    @Test
    void thereAre59Templates() {
        Set<String> templates = RouteNormalizer.templates();
        String type = "(films|people|planets|species|starships|vehicles)";
        long api = templates.stream().filter(t -> t.matches("/api/" + type + "(/.+)?")).count();
        long pages = templates.stream().filter(t -> t.matches("/resource/" + type + "(/.+)?")).count();
        long other = templates.stream().filter(RouteNormalizer.OTHER::equals).count();

        assertEquals(18, api, "list, random and {id} of each record type");
        assertEquals(18, pages, "list, {id} and {id}.md of each record type");
        assertEquals(1, other);
        assertEquals(22, templates.size() - api - pages - other, "fixed addresses");
        assertEquals(59, templates.size(), NOTES);
        assertEquals(templates.size(), RouteNormalizer.TEMPLATE_COUNT, NOTES);
    }
}
