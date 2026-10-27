package com.eldermoraes.seo;

import com.eldermoraes.stats.RouteNormalizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one source of page metadata (2.10.0): seo/site.json, read by the server
 * (SeoMetadataRegistry) and by the site (webui/src/site.ts). Plain JUnit, no
 * Quarkus boot: the rules of the file are checked where it is read.
 */
class SeoSiteFileTest {

    private static final Set<String> TYPES = Set.of("people", "films", "planets", "species", "starships", "vehicles");

    private final SeoSiteFile site = SeoSiteFile.load();

    @Test
    void theFileIsReadWithTheSixTypes() {
        assertEquals(TYPES, site.resources().stream().map(SeoSiteFile.ResourceType::type).collect(Collectors.toSet()));
        assertEquals(6, site.resources().size());
        assertEquals(8, site.pages().size());
    }

    @Test
    void everyPlaceholderIsADeclaredFieldOrTheId() {
        for (SeoSiteFile.ResourceType resource : site.resources()) {
            Set<String> allowed = new HashSet<>(resource.detail().fields());
            allowed.add("id");
            for (String template : List.of(resource.detail().title(), resource.detail().description())) {
                assertTrue(allowed.containsAll(SeoSiteFile.placeholders(template)), resource.type() + ": " + template);
            }
            for (String template : List.of(resource.detail().fallbackTitle(), resource.detail().fallbackDescription())) {
                assertEquals(Set.of("id"), SeoSiteFile.placeholders(template), resource.type() + ": " + template);
            }
        }
    }

    @Test
    void everyTitleEndsWithTheBrand() {
        List<String> titles = new ArrayList<>();
        titles.add(site.notFound().title());
        site.pages().forEach(page -> titles.add(page.title()));
        for (SeoSiteFile.ResourceType resource : site.resources()) {
            titles.add(resource.list().title());
            titles.add(resource.detail().title());
            titles.add(resource.detail().fallbackTitle());
        }
        for (String title : titles) {
            assertTrue(title.endsWith(site.brandSuffix()), title);
        }
        assertEquals(" | swapi.build", site.brandSuffix());
    }

    // No lastmod anywhere: there is no real per-address date of change (the data
    // has not changed since 2014), and the build or deploy date would be a lie.
    @Test
    void thereIsNoLastmod() throws IOException {
        String raw = Files.readString(Path.of("src/main/resources/seo/site.json"), StandardCharsets.UTF_8);
        assertFalse(raw.contains("lastmod"));
    }

    // The checklist of a new page (slice B) as a test: /stats counts it under its
    // own template, and the HTML cache filter stamps it.
    @Test
    void everyPageIsCountedAndCachedLikeAPage() throws IOException {
        Pattern spa = Pattern.compile(applicationProperties().getProperty("quarkus.http.filter.spa.matches"));
        for (SeoSiteFile.Page page : site.pages()) {
            assertEquals(Optional.of(page.path()), RouteNormalizer.normalize(page.path()), page.path());
            assertTrue(spa.matcher(page.path()).matches(), page.path());
        }
        for (SeoSiteFile.ResourceType resource : site.resources()) {
            assertTrue(spa.matcher("/resource/" + resource.type()).matches(), resource.type());
            assertTrue(spa.matcher("/resource/" + resource.type() + "/1").matches(), resource.type());
        }
    }

    // The batches (2.10.0): one type at a time, films first.
    @Test
    void onlyFilmsAreIndexable() {
        Set<String> indexable = site.resources().stream()
                .filter(resource -> resource.detail().indexable())
                .map(SeoSiteFile.ResourceType::type)
                .collect(Collectors.toSet());
        assertEquals(Set.of("films"), indexable);
    }

    // Slice E1 (2.11.0): the server draws the <main> of exactly these four pages.
    @Test
    void ssrMarksExactlyTheFourFixedPages() {
        Set<String> ssr = site.pages().stream()
                .filter(SeoSiteFile.Page::ssr)
                .map(SeoSiteFile.Page::page)
                .collect(Collectors.toSet());
        assertEquals(Set.of("home", "docs", "mcp", "about"), ssr);
    }

    // The short name of a page or a type, for the BreadcrumbList and the home rows.
    // The home has none: it is never a crumb after Home.
    @Test
    void everyPageButTheHomeAndEveryTypeHasALabel() {
        for (SeoSiteFile.Page page : site.pages()) {
            if (page.path().equals("/")) {
                assertEquals(null, page.label(), page.path());
            } else {
                assertTrue(page.label() != null && !page.label().isBlank(), page.path());
            }
        }
        for (SeoSiteFile.ResourceType resource : site.resources()) {
            assertTrue(resource.label() != null && !resource.label().isBlank(), resource.type());
        }
        assertEquals("MCP server", site.pages().stream().filter(p -> p.page().equals("mcp")).findFirst().orElseThrow().label());
        assertEquals("Films", site.resource("films").orElseThrow().label());
    }

    @Test
    void aNonBooleanSsrFails() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SeoSiteFile.load("seo/invalid-ssr.json"));
        assertTrue(error.getMessage().contains("pages[2].ssr"), error.getMessage());
        assertTrue(error.getMessage().contains("must be true or false"), error.getMessage());
    }

    @Test
    void aPlaceholderOutsideTheFieldsFailsWithItsName() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SeoSiteFile.load("seo/invalid-placeholder.json"));
        assertTrue(error.getMessage().contains("{director}"), error.getMessage());
        assertTrue(error.getMessage().contains("seo/invalid-placeholder.json"), error.getMessage());
    }

    @Test
    void aMissingFileFailsWithItsPath() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SeoSiteFile.load("seo/does-not-exist.json"));
        assertTrue(error.getMessage().contains("seo/does-not-exist.json"), error.getMessage());
    }

    @Test
    void anUnknownKeyFails() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SeoSiteFile.load("seo/invalid-unknown-key.json"));
        assertTrue(error.getMessage().contains("lastmod"), error.getMessage());
    }

    @Test
    void renderReplacesTheKeysAndLeavesTheRestAlone() {
        assertEquals(Optional.of("A New Hope #1 {not a key} | x"),
                SeoSiteFile.render("{title} #{id} {not a key} | x", Map.of("title", "A New Hope", "id", "1")));
        assertEquals(Optional.of("4"), SeoSiteFile.render("{episode_id}", Map.of("episode_id", "4")));
    }

    // A record of an enabled type with an empty field must not get "null" in its
    // title: the caller falls back to the fallback title.
    @Test
    void renderIsEmptyWhenAValueIsMissing() {
        assertEquals(Optional.empty(), SeoSiteFile.render("{title} #{id}", Map.of("id", "1")));
        assertEquals(Optional.empty(), SeoSiteFile.render("{title}", Map.of("title", " ")));
    }

    static Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
