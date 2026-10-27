package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.SeoSiteFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failure to read the OpenAPI document degrades only /docs (slice E, 2.11.0):
 * no exception at startup, /docs leaves as the shell without the marker, and
 * the other pages still render. The swapi-update script restarts before it
 * knows whether the new version comes up, so a startup failure here would take
 * /api and /mcp down until a manual rollback.
 */
class OpenApiDocsUnavailableTest {

    @Test
    void anEmptyDocumentMarksDocsUnavailableWithoutThrowing() {
        OpenApiDocs docs = OpenApiDocs.from(() -> "{\"openapi\":\"3.1.0\",\"paths\":{}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(docs.view().isEmpty());
    }

    @Test
    void aSourceThatThrowsMarksDocsUnavailableWithoutThrowing() {
        OpenApiDocs docs = OpenApiDocs.from(() -> {
            throw new IllegalStateException("no document");
        });
        assertTrue(docs.view().isEmpty());
    }

    @Test
    void notJsonMarksDocsUnavailableWithoutThrowing() {
        OpenApiDocs docs = OpenApiDocs.from(() -> "not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(docs.view().isEmpty());
    }

    @Test
    void withoutTheDocumentDocsHasNoMainAndTheOthersStillRender() {
        OpenApiDocs docs = OpenApiDocs.from(() -> "{}".getBytes(StandardCharsets.UTF_8));
        // Plain JUnit: the typed templates exist only in a Quarkus build, so the
        // about page here is a stand-in; the real renders are ServerRenderedPagesTest.
        SsrPages pages = new SsrPages() {
            @Override
            public String about() {
                return "<p>About</p>";
            }
        };
        pages.openApi = docs;
        ServerRendering rendering = new ServerRendering(SeoSiteFile.load(), pages);
        assertTrue(rendering.main("docs", "https://preview.example").isEmpty());
        assertEquals("<main id=\"main-content\" tabindex=\"-1\" data-ssr-page=\"about\"><p>About</p></main>",
                rendering.main("about", "https://preview.example").orElseThrow());
        assertTrue(rendering.main("stats", "https://preview.example").isEmpty());
    }

    // The order of localeCompare for these paths: punctuation before letters.
    @Test
    void theComparatorPutsPunctuationBeforeLetters() {
        Comparator<String> order = OpenApiDocs.PATH_ORDER;
        List<String> sorted = List.of("/api/people/random", "/api/people/{id}", "/api/people", "/api/planets")
                .stream().sorted(order).toList();
        assertEquals(List.of("/api/people", "/api/people/{id}", "/api/people/random", "/api/planets"), sorted);
    }
}
