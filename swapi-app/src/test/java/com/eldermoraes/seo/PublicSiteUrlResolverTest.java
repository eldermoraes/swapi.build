package com.eldermoraes.seo;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One meaning for swapi.public-base-url (slice E, 2.11.0): the API base
 * (https://host/api), as the MCP tools and SwapiBaseUrlOverrideTest already read
 * it. The site root is derived from it by dropping a final /api. Plain JUnit: the
 * request is a proxy that answers only the calls the resolver makes.
 */
class PublicSiteUrlResolverTest {

    @Test
    void anApiBaseOverrideGivesTheSiteRootWithoutApi() {
        PublicSiteUrlResolver urls = resolver(Optional.of("https://x.example/api"));
        RoutingContext rc = request(Map.of());
        assertEquals("https://x.example", urls.siteRoot(rc));
        assertEquals("https://x.example/api", urls.apiBase(rc));
        assertEquals("https://x.example/mcp", urls.absoluteUrl(rc, "/mcp"));
    }

    @Test
    void aTrailingSlashOnTheOverrideChangesNothing() {
        PublicSiteUrlResolver urls = resolver(Optional.of("https://x.example/api/"));
        RoutingContext rc = request(Map.of());
        assertEquals("https://x.example", urls.siteRoot(rc));
        assertEquals("https://x.example/api", urls.apiBase(rc));
    }

    // An override without /api (the old reading, site root) still works as a root.
    @Test
    void anOverrideWithoutApiIsTheRoot() {
        PublicSiteUrlResolver urls = resolver(Optional.of("https://x.example/"));
        RoutingContext rc = request(Map.of());
        assertEquals("https://x.example", urls.siteRoot(rc));
        assertEquals("https://x.example/api", urls.apiBase(rc));
    }

    @Test
    void withoutTheOverrideTheRequestHeadersDecide() {
        PublicSiteUrlResolver urls = resolver(Optional.empty());
        RoutingContext rc = request(Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "preview.example"));
        assertEquals("https://preview.example", urls.siteRoot(rc));
        assertEquals("https://preview.example/api", urls.apiBase(rc));
        assertEquals("https://preview.example/docs/mcp", urls.absoluteUrl(rc, "/docs/mcp"));
    }

    @Test
    void withoutHeadersTheRequestItselfDecides() {
        PublicSiteUrlResolver urls = resolver(Optional.empty());
        RoutingContext rc = request(Map.of());
        assertEquals("http://localhost:8081", urls.siteRoot(rc));
    }

    private static PublicSiteUrlResolver resolver(Optional<String> override) {
        PublicSiteUrlResolver urls = new PublicSiteUrlResolver();
        urls.publicBaseUrl = override;
        return urls;
    }

    private static RoutingContext request(Map<String, String> headers) {
        HttpServerRequest request = (HttpServerRequest) Proxy.newProxyInstance(
                PublicSiteUrlResolverTest.class.getClassLoader(), new Class<?>[]{HttpServerRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get(String.valueOf(args[0]));
                    case "scheme" -> "http";
                    case "host" -> "localhost:8081";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (RoutingContext) Proxy.newProxyInstance(
                PublicSiteUrlResolverTest.class.getClassLoader(), new Class<?>[]{RoutingContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "request" -> request;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
