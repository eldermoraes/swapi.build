package com.eldermoraes;

import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * An address under /api has one spelling (issue #105). Before RESTEasy
 * Reactive matches a route, Vert.x collapses repeated slashes
 * ("/api//people/1"), removes dot segments ("/api/./people/1") and decodes
 * percent-encoded letters ("/api/%70eople/1"), and RESTEasy Reactive then
 * drops matrix parameters ("/api/people;x=1/1"). Each of those answered 200
 * with the body of the canonical address, cached like it.
 *
 * So the raw path, as the client wrote it, must be the path the route was
 * matched on: "/api" plus the decoded, normalised path of UriInfo. Anything
 * else is the 404 of the contract. Caddy forwards the path as it is, so the
 * rule lives here and nowhere in front.
 *
 * One trailing slash is allowed on purpose: swapi.dev writes every address
 * with one, and clients ported from it send it. More than one is an alias.
 *
 * Runs after CanonicalIdFilter (issue #55, default priority USER), so an alias
 * of the id keeps its own message ("No people found with id 01").
 */
@Provider
@Priority(Priorities.USER + 1)
public class CanonicalPathFilter implements ContainerRequestFilter {

    private static final String API = "/api";

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
        String written = request.path();
        String canonical = API + withoutTrailingSlashes(context.getUriInfo().getPath());
        if (!canonical.equals(withoutOneTrailingSlash(written))) {
            context.abortWith(ApiNotFoundMapper.notFoundAt(written));
        }
    }

    private static String withoutTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static String withoutOneTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
