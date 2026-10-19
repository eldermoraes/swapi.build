package com.eldermoraes.stats;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;

/**
 * Counts every request by route template before anything else can end it.
 *
 * Priority 400 runs ahead of the CORS handler (300), the MCP transport filter
 * (250) and the SEO routes (100) — a higher priority runs earlier — so a
 * request those filters answer themselves (a 405 on GET /mcp, robots.txt, the
 * SPA HTML) is still counted. Only the path is read; the filter never looks at
 * the client's address or headers.
 *
 * OPTIONS (CORS preflight) and HEAD are not counted: a browser MCP client sends
 * a preflight before each POST, which would double the /mcp count, and HEAD is
 * what monitors use.
 */
public class RouteCountingFilter {

    @Inject
    RouteStats stats;

    @RouteFilter(400)
    void count(RoutingContext rc) {
        HttpMethod method = rc.request().method();
        if (!HttpMethod.OPTIONS.equals(method) && !HttpMethod.HEAD.equals(method)) {
            RouteNormalizer.normalize(rc.normalizedPath()).ifPresent(stats::record);
        }
        rc.next();
    }
}
