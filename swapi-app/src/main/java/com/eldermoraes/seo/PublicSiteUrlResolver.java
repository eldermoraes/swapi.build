package com.eldermoraes.seo;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Optional;

@ApplicationScoped
public class PublicSiteUrlResolver {

    @ConfigProperty(name = "swapi.public-base-url")
    Optional<String> publicBaseUrl;

    public String siteRoot(RoutingContext rc) {
        return normalize(publicBaseUrl.orElseGet(() -> requestRoot(rc)));
    }

    public String absoluteUrl(RoutingContext rc, String path) {
        return URI.create(siteRoot(rc) + "/").resolve(stripLeadingSlash(path)).toString();
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

    private static String normalize(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
