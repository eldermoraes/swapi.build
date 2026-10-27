package com.eldermoraes.seo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class SeoMetadataRegistry {

    // The 404 document (2.9.1). One line each: seo-parity.test.ts reads them with
    // javaConstant() and compares them with seo.ts.
    static final String NOT_FOUND_TITLE = "Page not found | swapi.build";
    static final String NOT_FOUND_DESCRIPTION = "This page does not exist on swapi.build. Browse the Star Wars API, its docs or the MCP server instead.";

    private final KnownEntities entities;

    @Inject
    public SeoMetadataRegistry(KnownEntities entities) {
        this.entities = entities;
    }

    // Titles and descriptions are mirrored by hand in webui/src/seo.ts until the
    // single metadata file of slice D; seo-parity.test.ts compares the two.
    // Intent words first, the brand "swapi.build" last; never "drop-in".
    private static final String HOME_DESCRIPTION = "Free SWAPI alternative: Star Wars REST JSON API and remote MCP server with people, films, planets, species, starships and vehicles (Episodes I–VI). No API key.";

    private static final List<SeoMetadata> ROUTES = List.of(
            route("/", "SWAPI alternative: free Star Wars API and MCP server | swapi.build", HOME_DESCRIPTION, "weekly", "1.0"),
            route("/docs", "SWAPI docs: Star Wars REST API endpoints and OpenAPI | swapi.build", "Star Wars API reference for swapi.build: REST endpoints, JSON response schemas and live examples, generated from the OpenAPI contract.", "weekly", "0.9"),
            route("/docs/mcp", "Star Wars MCP server: remote, no API key | swapi.build", "Add the Star Wars MCP server to Claude, OpenAI Codex, GitHub Copilot or IBM Bob in one step. Remote Streamable HTTP at /mcp, read-only, no API key.", "weekly", "0.8"),
            route("/docs/webmcp", "Star Wars WebMCP tools in the browser | swapi.build", "Explore Star Wars together with a browser agent using experimental WebMCP tools.", "weekly", "0.8"),
            route("/about", "About: a SWAPI alternative built with Quarkus | swapi.build", "Learn why swapi.build exists, how it is built with Quarkus and GraalVM, and how to contribute.", "monthly", "0.7"),
            route("/resource/people", "Star Wars People API: JSON data | swapi.build", "Browse Star Wars people records as JSON from the free swapi.build REST API and MCP server.", "weekly", "0.7"),
            route("/resource/films", "Star Wars Films API: JSON data | swapi.build", "Browse Star Wars film records as JSON from the free swapi.build REST API and MCP server.", "weekly", "0.7"),
            route("/resource/planets", "Star Wars Planets API: JSON data | swapi.build", "Browse Star Wars planet records as JSON from the free swapi.build REST API and MCP server.", "weekly", "0.7"),
            route("/resource/species", "Star Wars Species API: JSON data | swapi.build", "Browse Star Wars species records as JSON from the free swapi.build REST API and MCP server.", "weekly", "0.7"),
            route("/resource/starships", "Star Wars Starships API: JSON data | swapi.build", "Browse Star Wars starship records as JSON from the free swapi.build REST API and MCP server.", "weekly", "0.7"),
            route("/resource/vehicles", "Star Wars Vehicles API: JSON data | swapi.build", "Browse Star Wars vehicle records as JSON from the free swapi.build REST API and MCP server.", "weekly", "0.7"),
            route("/stats", "Usage stats | swapi.build", "Anonymous request counts for the swapi.build REST API, MCP server and site, per route and per group, for the last 24 hours, 7 days or 30 days.", "daily", "0.4"),
            route("/privacy", "Privacy Policy | swapi.build", "Plain-language privacy policy for swapi.build, the free public Star Wars API and MCP server.", "yearly", "0.3"),
            route("/terms", "Terms of Use | swapi.build", "Terms of use for swapi.build, the free public Star Wars API and MCP server.", "yearly", "0.3"));

    public List<SeoMetadata> sitemapRoutes() {
        return ROUTES.stream().filter(SeoMetadata::sitemap).toList();
    }

    /**
     * The metadata of a page, or {@link #notFound} when the path is not a page.
     * The path is matched as given (callers pass the raw request path, not the
     * percent-decoded one), so /resource/people/%31 is not the page of id 1.
     */
    public SeoMetadata forPath(String rawPath) {
        String path = normalizePath(rawPath);
        for (SeoMetadata route : ROUTES) {
            if (route.path().equals(path)) {
                return route;
            }
        }
        SeoMetadata resourceDetail = resourceDetail(path);
        if (resourceDetail != null) {
            return resourceDetail;
        }
        return notFound(path);
    }

    public boolean isKnownDocumentPath(String rawPath) {
        String path = normalizePath(rawPath);
        return ROUTES.stream().anyMatch(route -> route.path().equals(path)) || resourceDetail(path) != null;
    }

    /** No canonical, noindex, out of the sitemap. Nothing of the path goes into the document. */
    public SeoMetadata notFound(String path) {
        return new SeoMetadata(path, null, NOT_FOUND_TITLE, NOT_FOUND_DESCRIPTION, SeoMetadata.NOINDEX, false, null, null);
    }

    private static SeoMetadata route(String path, String title, String description, String frequency, String priority) {
        return new SeoMetadata(path, path, title, description, SeoMetadata.INDEX_FOLLOW, true, frequency, priority);
    }

    /** Only an entity that exists, with its id in the canonical form (KnownEntities). */
    private SeoMetadata resourceDetail(String path) {
        String[] parts = path.split("/");
        if (parts.length == 4 && "resource".equals(parts[1]) && entities.exists(parts[2], parts[3])) {
            String resourceTitle = switch (parts[2]) {
                case "people" -> "People";
                case "films" -> "Films";
                case "planets" -> "Planets";
                case "species" -> "Species";
                case "starships" -> "Starships";
                case "vehicles" -> "Vehicles";
                default -> null;
            };
            if (resourceTitle != null) {
                // The singular noun is the modifier: "film record", not "films record".
                String noun = switch (parts[2]) {
                    case "people", "species" -> parts[2];
                    default -> parts[2].substring(0, parts[2].length() - 1);
                };
                return new SeoMetadata(path, "/resource/" + parts[2], resourceTitle + " #" + parts[3] + " | swapi.build",
                        "Star Wars " + noun + " record #" + parts[3] + " from the swapi.build REST API.",
                        SeoMetadata.NOINDEX_FOLLOW, false, null, null);
            }
        }
        return null;
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        String path = rawPath.split("[?#]", 2)[0];
        if (path.isBlank()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
