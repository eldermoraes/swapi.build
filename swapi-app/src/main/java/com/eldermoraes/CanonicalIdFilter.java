package com.eldermoraes;

import com.eldermoraes.seo.KnownEntities;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * A record has one address under /api: its id in the canonical form, written
 * literally (issue #55). The resources take the id as a Java int, and the
 * conversion also accepts "01" and "+1"; the path reaches it already decoded
 * ("%31" is "1") and without its matrix parameters ("1;x=y" is "1"). Each of
 * those answered 200 with the record of id 1, cached like the real address,
 * while the pages and the MCP resources refused them.
 *
 * So the id is read here from the raw path, as the client wrote it, before the
 * resource runs, and anything but a canonical id is the 404 of the contract.
 * Only a route with a path parameter named "id" is looked at: the lists and
 * /random have none. The parameter stays an int in the resources, which keeps
 * /openapi.json as it was.
 *
 * A trailing slash is ignored on purpose: /api/people/1/ answered 200 before
 * this filter and is not part of that issue.
 */
@Provider
public class CanonicalIdFilter implements ContainerRequestFilter {

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
        UriInfo uriInfo = context.getUriInfo();
        if (!uriInfo.getPathParameters().containsKey("id")) {
            return;
        }
        String written = lastSegment(request.path());
        if (!KnownEntities.isCanonicalId(written)) {
            context.abortWith(ApiNotFoundMapper.notFound(uriInfo, written));
        }
    }

    /** The last segment of a raw path, trailing slashes ignored. */
    private static String lastSegment(String rawPath) {
        int end = rawPath.length();
        while (end > 0 && rawPath.charAt(end - 1) == '/') {
            end--;
        }
        return rawPath.substring(rawPath.lastIndexOf('/', end - 1) + 1, end);
    }
}
