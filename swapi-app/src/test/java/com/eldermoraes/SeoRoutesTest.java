package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class SeoRoutesTest {

    @Test
    void robotsTxtBypassesSpaFallbackAndUsesForwardedBaseUrl() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/robots.txt")
        .then()
                .statusCode(200)
                .contentType(containsString("text/plain"))
                .body(allOf(
                        containsString("User-agent: *"),
                        containsString("Allow: /"),
                        containsString("Sitemap: https://preview.example/sitemap.xml"),
                        not(containsString("<!doctype html>"))));
    }

    @Test
    void sitemapXmlBypassesSpaFallbackAndUsesForwardedBaseUrl() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/sitemap.xml")
        .then()
                .statusCode(200)
                .contentType(containsString("xml"))
                .body(allOf(
                        containsString("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"),
                        containsString("<loc>https://preview.example/</loc>"),
                        containsString("<loc>https://preview.example/docs</loc>"),
                        containsString("<loc>https://preview.example/docs/mcp</loc>"),
                        containsString("<loc>https://preview.example/about</loc>"),
                        containsString("<loc>https://preview.example/resource/people</loc>"),
                        containsString("<loc>https://preview.example/resource/films</loc>"),
                        containsString("<loc>https://preview.example/resource/planets</loc>"),
                        containsString("<loc>https://preview.example/resource/species</loc>"),
                        containsString("<loc>https://preview.example/resource/starships</loc>"),
                        containsString("<loc>https://preview.example/resource/vehicles</loc>"),
                        not(containsString("<!doctype html>"))));
    }

    @Test
    void homeHtmlHasServerRenderedSeoMetadata() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<title>SWAPI - The Star Wars API</title>"),
                        containsString("<meta name=\"description\""),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/\""),
                        containsString("<meta property=\"og:url\" content=\"https://preview.example/\""),
                        containsString("<meta property=\"og:image\" content=\"https://preview.example/og-image.png\""),
                        containsString("<meta name=\"twitter:card\" content=\"summary_large_image\""),
                        containsString("<script type=\"application/ld+json\""),
                        containsString("schema.org"),
                        not(containsString("https://swapi.build"))));
    }

    @Test
    void docsHtmlHasRouteSpecificCanonical() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/docs")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<title>Documentation - SWAPI</title>"),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/docs\""),
                        containsString("<meta property=\"og:url\" content=\"https://preview.example/docs\""),
                        containsString("OpenAPI"),
                        not(containsString("All the Star Wars data you've ever wanted: People"))));
    }

    @Test
    void unknownDocumentRouteIsNoindex() {
        given()
                .accept("text/html")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "preview.example")
        .when()
                .get("/not-a-real-page")
        .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(allOf(
                        containsString("<meta name=\"robots\" content=\"noindex,follow\""),
                        containsString("<link rel=\"canonical\" href=\"https://preview.example/\""),
                        not(containsString("https://preview.example/not-a-real-page"))));
    }

    @Test
    void ogImageIsServedAsPng() {
        given()
        .when()
                .get("/og-image.png")
        .then()
                .statusCode(200)
                .contentType(containsString("image/png"));
    }
}
