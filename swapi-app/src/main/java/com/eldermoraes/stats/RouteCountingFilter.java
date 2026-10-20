package com.eldermoraes.stats;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Counts every request by route template before anything else can end it, and
 * times how long the server takes to answer it.
 *
 * Priority 400 runs ahead of the CORS handler (300), the MCP transport filter
 * (250) and the SEO routes (100) — a higher priority runs earlier — so a
 * request those filters answer themselves (a 405 on GET /mcp, robots.txt, the
 * SPA HTML) is still counted and timed. Only the path is read; the filter never
 * looks at the client's address or headers.
 *
 * The time is server time: from this filter, the first route handler to see the
 * request, to the end of the response, when its last byte has been handed to
 * the connection. The network between the client and the server is not in it.
 * A request whose connection closes before the response ends is counted but not
 * timed. The time goes under the same route template as the count.
 *
 * OPTIONS (CORS preflight) and HEAD are neither counted nor timed: a browser
 * MCP client sends a preflight before each POST, which would double the /mcp
 * count, and HEAD is what monitors use. Static files are left out by
 * {@link RouteNormalizer}.
 */
public class RouteCountingFilter {

    @Inject
    RouteStats stats;

    @RouteFilter(400)
    void count(RoutingContext rc) {
        HttpMethod method = rc.request().method();
        if (!HttpMethod.OPTIONS.equals(method) && !HttpMethod.HEAD.equals(method)) {
            Optional<String> route = RouteNormalizer.normalize(rc.normalizedPath());
            if (route.isPresent()) {
                String template = route.get();
                long start = System.nanoTime();
                stats.record(template);
                rc.addEndHandler(done -> {
                    if (done.succeeded()) {
                        stats.recordDuration(template, System.nanoTime() - start);
                    }
                });
            }
        }
        rc.next();
    }
}
