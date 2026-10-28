package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

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
}
