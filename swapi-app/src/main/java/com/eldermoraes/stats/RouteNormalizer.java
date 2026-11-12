package com.eldermoraes.stats;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
 *
 * {@link #templates()} lists the closed set and {@link #TEMPLATE_COUNT} is its
 * size, the number every sizing note of the statistics starts from.
 */
public final class RouteNormalizer {

    public static final String OTHER = "other";

    static final Set<String> RESOURCES = Set.of("films", "people", "planets", "species", "starships", "vehicles");

    private static final Set<String> FIXED = Set.of(
            "/", "/api", "/api/stats", "/mcp", "/openapi.json", "/robots.txt", "/sitemap.xml",
            "/llms.txt", "/docs", "/docs/mcp", "/docs/webmcp", "/faq", "/about", "/privacy", "/terms", "/stats",
            // 2.14.0 (slice G1): /llms-full.txt and the Markdown copies of the fixed pages.
            "/llms-full.txt", "/index.html.md", "/docs.md", "/docs/mcp.md", "/faq.md", "/about.md");

    /**
     * How many templates there are: 22 fixed addresses, list, {@code random} and
     * {@code {id}} of each of the 6 record types under {@code /api} (18), list,
     * {@code {id}} and {@code {id}.md} of each under {@code /resource} (18), and
     * {@link #OTHER}. The worst cases in {@link RouteStats}, at
     * {@link SnapshotFile#MAX_BYTES} and in docs/DEPLOY.md ("Usage counters",
     * "Server times") are multiples of it: a change here goes there too, and
     * {@code RouteNormalizerTest} fails until this number is the size of
     * {@link #templates()}.
     */
    public static final int TEMPLATE_COUNT = 59;

    private static final Set<String> TEMPLATES = listTemplates();

    private RouteNormalizer() {
    }

    /** Every template {@link #normalize} can return, sorted. */
    public static Set<String> templates() {
        return TEMPLATES;
    }

    private static Set<String> listTemplates() {
        Set<String> all = new TreeSet<>(FIXED);
        for (String resource : RESOURCES) {
            all.add("/api/" + resource);
            all.add("/api/" + resource + "/random");
            all.add("/api/" + resource + "/{id}");
            all.add("/resource/" + resource);
            all.add("/resource/" + resource + "/{id}");
            all.add("/resource/" + resource + "/{id}.md");
        }
        all.add(OTHER);
        return Collections.unmodifiableSet(all);
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
        // Cut by hand: split("[?#]") would compile a regex on every request.
        String path = rawPath;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '?' || c == '#') {
                path = path.substring(0, i);
                break;
            }
        }
        if (path.isEmpty()) {
            return Optional.of(OTHER);
        }
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
                // 2.14.0: the Markdown copy of a record page is its own template,
                // never counted as the record page itself.
                if ("resource".equals(parts[1]) && parts[3].endsWith(".md") && parts[3].length() > 3) {
                    return Optional.of(base + "/{id}.md");
                }
                return Optional.of(base + "/{id}");
            }
        }
        return Optional.of(OTHER);
    }
}
