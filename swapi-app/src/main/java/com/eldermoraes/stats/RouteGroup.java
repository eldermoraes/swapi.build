package com.eldermoraes.stats;

/**
 * The coarse family a route template belongs to, for the grouped view of
 * {@code /stats}. Like the templates themselves, the set is closed.
 */
public enum RouteGroup {
    /** {@code /api}, {@code /api/*} and the OpenAPI contract. */
    REST("rest"),
    /** The MCP endpoint. */
    MCP("mcp"),
    /** The SPA pages: home, docs, resource browser, about, legal, stats. */
    PAGES("pages"),
    /** What crawlers fetch: {@code robots.txt} and {@code sitemap.xml}. */
    SEO("seo"),
    /** Everything {@link RouteNormalizer} could not place. */
    OTHER("other");

    private final String id;

    RouteGroup(String id) {
        this.id = id;
    }

    /** Stable id used in the API ({@code rest}, {@code mcp}, {@code pages}, {@code seo}, {@code other}). */
    public String id() {
        return id;
    }

    /** @param route a template from {@link RouteNormalizer} */
    public static RouteGroup of(String route) {
        if (route == null || RouteNormalizer.OTHER.equals(route)) {
            return OTHER;
        }
        if (route.equals("/api") || route.startsWith("/api/") || route.equals("/openapi.json")) {
            return REST;
        }
        if (route.equals("/mcp")) {
            return MCP;
        }
        if (route.equals("/robots.txt") || route.equals("/sitemap.xml")) {
            return SEO;
        }
        if (route.startsWith("/")) {
            return PAGES;
        }
        return OTHER;
    }
}
