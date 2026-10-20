package com.eldermoraes.stats;

import java.util.Optional;
import java.util.Set;

/**
 * Maps a request path to the route template it is counted under.
 *
 * The set of results is closed: every known route has a fixed template
 * ({@code /api/people/{id}}, {@code /mcp}, {@code /docs}, ...) and anything else
 * — scanners, typos, legacy transports — collapses into {@link #OTHER}. That is
 * what keeps the counters bounded in memory no matter what the internet sends,
 * and what keeps ids, search terms and query strings (which could carry
 * something a client typed) out of the statistics.
 *
 * Static files of the SPA ({@code /assets/*}, the favicon, the Open Graph image)
 * are not counted at all: every page view fetches several of them, so they would
 * only drown the routes that matter.
 */
public final class RouteNormalizer {

    public static final String OTHER = "other";

    static final Set<String> RESOURCES = Set.of("films", "people", "planets", "species", "starships", "vehicles");

    private static final Set<String> FIXED = Set.of(
            "/", "/api", "/api/stats", "/mcp", "/openapi.json", "/robots.txt", "/sitemap.xml",
            "/docs", "/docs/mcp", "/docs/webmcp", "/about", "/privacy", "/terms", "/stats");

    private RouteNormalizer() {
    }

    /**
     * @param rawPath the request path, without the query string (a query string,
     *                if present, is ignored)
     * @return the route template, {@link #OTHER} for an unknown path, or empty for
     *         a static file that is not counted
     */
    public static Optional<String> normalize(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return Optional.of(OTHER);
        }
        String path = rawPath.split("[?#]", 2)[0];
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.startsWith("/assets/") || path.equals("/favicon.svg") || path.equals("/og-image.png")) {
            return Optional.empty();
        }
        if (FIXED.contains(path)) {
            return Optional.of(path);
        }
        String[] parts = path.split("/", -1);
        // "/api/people" -> ["", "api", "people"]
        if (parts.length >= 3 && ("api".equals(parts[1]) || "resource".equals(parts[1]))
                && RESOURCES.contains(parts[2])) {
            String base = "/" + parts[1] + "/" + parts[2];
            if (parts.length == 3) {
                return Optional.of(base);
            }
            if (parts.length == 4 && !parts[3].isEmpty()) {
                if ("api".equals(parts[1]) && "random".equals(parts[3])) {
                    return Optional.of(base + "/random");
                }
                return Optional.of(base + "/{id}");
            }
        }
        return Optional.of(OTHER);
    }
}
