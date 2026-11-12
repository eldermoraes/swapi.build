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
 * Marks everything deterministic under /api as cacheable, by the browser and
 * by a shared cache.
 *
 * The data is static JSON embedded in the binary, so a response only changes
 * on a release — but a release invalidates no cache: not the browser's, and
 * not a shared one in front of the self-hosted origin or on the client's side.
 * That is why both lifetimes are short (the value and its reasons live in
 * swapi.cache-control.public): no cache treats an entry as fresh for more
 * than five minutes after a data fix.
 *
 * Deny-list policy: every new /api endpoint is born cacheable;
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
            // this a shared cache would serve one origin's Access-Control-Allow-Origin
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
        // Only 200 and 404 get the policy: anything else (a 405, a 5xx) leaves without it.
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
