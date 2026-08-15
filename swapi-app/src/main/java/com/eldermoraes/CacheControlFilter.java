package com.eldermoraes;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Marks everything deterministic under /api as edge-cacheable.
 *
 * The data is static JSON embedded in the binary, so a response only changes
 * on a new deploy — and Vercel's cache key includes the deployment URL, which
 * invalidates the entry automatically. That is why the edge TTL is high and
 * the browser TTL is short: the browser cache is NOT invalidated by a deploy.
 *
 * Deny-list policy: every new /api endpoint is born edge-cacheable;
 * a non-deterministic endpoint must be added to the exclusion. The header is
 * only written if the response does not already carry a Cache-Control, so a
 * resource can opt out (e.g. no-store) by setting the header itself.
 */
@Provider
public class CacheControlFilter implements ContainerResponseFilter {

    private static final String RANDOM = "random";

    // Single definition in application.properties, shared with the HTTP filter
    // that covers /openapi.json. Package-private: the Quarkus idiom for field
    // injection without reflection.
    @ConfigProperty(name = "swapi.cache-control.public")
    String cacheControl;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        // Does not overwrite a Cache-Control the resource set on purpose.
        if (isCacheable(request, response)
                && !response.getHeaders().containsKey(HttpHeaders.CACHE_CONTROL)) {
            response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, cacheControl);
            // The CORS filter echoes the request Origin and emits no Vary. Without
            // this the edge would serve one origin's Access-Control-Allow-Origin
            // to another — and the Origin-less variant to a browser client.
            // add, not putSingle: preserves any existing Vary (e.g. Accept-Encoding).
            response.getHeaders().add(HttpHeaders.VARY, "Origin");
        }
    }

    private boolean isCacheable(ContainerRequestContext request, ContainerResponseContext response) {
        if (!HttpMethod.GET.equals(request.getMethod())
                && !HttpMethod.HEAD.equals(request.getMethod())) {
            return false;
        }
        // The edge only caches 200/404 (never 5xx) — marking anything else is pointless.
        if (response.getStatus() != 200 && response.getStatus() != 404) {
            return false;
        }
        return !isRandom(request);
    }

    private boolean isRandom(ContainerRequestContext request) {
        List<PathSegment> segments = request.getUriInfo().getPathSegments();
        return !segments.isEmpty()
                && RANDOM.equals(segments.get(segments.size() - 1).getPath());
    }
}
