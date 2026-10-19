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
            "/, /",
            "/docs, /docs",
            "/docs/mcp, /docs/mcp",
            "/docs/webmcp, /docs/webmcp",
            "/about, /about",
            "/privacy, /privacy",
            "/terms, /terms",
            "/resource/planets, /resource/planets",
            "/resource/planets/3, /resource/planets/{id}",
    })
    void knownRoutesMapToTheirTemplate(String path, String expected) {
        assertEquals(Optional.of(expected), RouteNormalizer.normalize(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/wp-login.php", "/.env", "/api/users", "/api/people/1/films", "/mcp/sse",
            "/mcp/messages/abc", "/resource/droids/1", "/resource/people/1/extra", "/API/people",
            "/docs/other", "/api/people//",
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
