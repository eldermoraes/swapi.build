package com.eldermoraes.seo;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Optional;

/**
 * The public URLs of the site, built per request (X-Forwarded-Proto and
 * X-Forwarded-Host, which Caddy overwrites in production), never from a fixed
 * domain.
 *
 * {@code swapi.public-base-url} has one meaning (2.11.0): the base of the API,
 * {@code https://host/api}, the way the MCP tools read it (SwapiTools). The site
 * root is that value without the final {@code /api}. An override without
 * {@code /api} still reads as the root. The REST API ignores the property: it
 * builds its URLs from UriInfo (BaseUrlFilter).
 */
@ApplicationScoped
public class PublicSiteUrlResolver {

    @ConfigProperty(name = "swapi.public-base-url")
    Optional<String> publicBaseUrl;

    /** The root of the site, without a trailing slash: {@code https://swapi.build}. */
    public String siteRoot(RoutingContext rc) {
        return publicBaseUrl.map(PublicSiteUrlResolver::rootOf).orElseGet(() -> requestRoot(rc));
    }

    /** The base of the API, without a trailing slash: {@code https://swapi.build/api}. */
    public String apiBase(RoutingContext rc) {
        return siteRoot(rc) + "/api";
    }

    public String absoluteUrl(RoutingContext rc, String path) {
        return URI.create(siteRoot(rc) + "/").resolve(stripLeadingSlash(path)).toString();
    }

    private static String rootOf(String base) {
        String root = withoutTrailingSlashes(base);
        if (root.endsWith("/api")) {
            root = root.substring(0, root.length() - "/api".length());
        }
        return withoutTrailingSlashes(root);
    }

    private static String requestRoot(RoutingContext rc) {
        String proto = firstHeader(rc, "X-Forwarded-Proto").orElse(rc.request().scheme());
        String host = firstHeader(rc, "X-Forwarded-Host").orElse(rc.request().host());
        return proto + "://" + host;
    }

    private static Optional<String> firstHeader(RoutingContext rc, String name) {
        String value = rc.request().getHeader(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.split(",", 2)[0].trim());
    }

    private static String withoutTrailingSlashes(String base) {
        String out = base;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
