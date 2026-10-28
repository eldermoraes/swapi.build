package com.eldermoraes.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteGroupTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api, rest",
            "/api/stats, rest",
            "/api/people, rest",
            "/api/people/{id}, rest",
            "/api/vehicles/random, rest",
            "/openapi.json, rest",
            "/mcp, mcp",
            "/, pages",
            "/docs, pages",
            "/docs/mcp, pages",
            "/docs/webmcp, pages",
            "/about, pages",
            "/privacy, pages",
            "/terms, pages",
            "/stats, pages",
            "/resource/planets, pages",
            "/resource/planets/{id}, pages",
            "/robots.txt, seo",
            "/sitemap.xml, seo",
            "/llms.txt, seo",
            "other, other",
    })
    void everyTemplateHasItsGroup(String route, String group) {
        assertEquals(group, RouteGroup.of(route).id());
    }

    // 2.14.0: every template that ends in .md, and /llms-full.txt, is what an agent fetches.
    @ParameterizedTest(name = "{0} -> seo")
    @CsvSource({"/llms-full.txt", "/index.html.md", "/docs.md", "/docs/mcp.md", "/about.md",
            "/resource/people/{id}.md", "/resource/films/{id}.md", "/resource/planets/{id}.md",
            "/resource/species/{id}.md", "/resource/starships/{id}.md", "/resource/vehicles/{id}.md"})
    void markdownAndLlmsFullAreSeo(String route) {
        assertEquals(RouteGroup.SEO, RouteGroup.of(route));
    }

    @Test
    void groupIdsAreTheOnesThePageKnows() {
        // src/main/webui/src/pages/stats.ts labels exactly these ids.
        assertEquals(List.of("rest", "mcp", "pages", "seo", "other"),
                Arrays.stream(RouteGroup.values()).map(RouteGroup::id).toList());
    }

    @Test
    void nothingUnknownLeavesOther() {
        assertEquals(RouteGroup.OTHER, RouteGroup.of(null));
        assertEquals(RouteGroup.OTHER, RouteGroup.of("weird"));
    }
}
