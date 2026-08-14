package com.eldermoraes.seo;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SeoMetadataRegistry {

    private static final String HOME_DESCRIPTION = "Free Star Wars REST API and MCP server with data about people, films, planets, species, starships and vehicles.";

    private static final List<SeoMetadata> ROUTES = List.of(
            route("/", "SWAPI - The Star Wars API", HOME_DESCRIPTION, "weekly", "1.0"),
            route("/docs", "Documentation - SWAPI", "OpenAPI documentation for the SWAPI REST API, including live examples and response schemas.", "weekly", "0.9"),
            route("/docs/mcp", "MCP Server - SWAPI", "Connect AI agents to the Star Wars API through the Streamable HTTP MCP server.", "weekly", "0.8"),
            route("/about", "About - SWAPI", "Learn why SWAPI exists, how it is built with Quarkus and GraalVM, and how to contribute.", "monthly", "0.7"),
            route("/resource/people", "People - SWAPI", "Browse Star Wars people records through the SWAPI REST API.", "weekly", "0.7"),
            route("/resource/films", "Films - SWAPI", "Browse Star Wars film records through the SWAPI REST API.", "weekly", "0.7"),
            route("/resource/planets", "Planets - SWAPI", "Browse Star Wars planet records through the SWAPI REST API.", "weekly", "0.7"),
            route("/resource/species", "Species - SWAPI", "Browse Star Wars species records through the SWAPI REST API.", "weekly", "0.7"),
            route("/resource/starships", "Starships - SWAPI", "Browse Star Wars starship records through the SWAPI REST API.", "weekly", "0.7"),
            route("/resource/vehicles", "Vehicles - SWAPI", "Browse Star Wars vehicle records through the SWAPI REST API.", "weekly", "0.7"),
            route("/privacy", "Privacy Policy - SWAPI", "Plain-language privacy policy for SWAPI, the public Star Wars API and MCP server.", "yearly", "0.3"),
            route("/terms", "Terms of Use - SWAPI", "Terms of use for SWAPI, the free public Star Wars API and MCP server.", "yearly", "0.3"));

    public List<SeoMetadata> sitemapRoutes() {
        return ROUTES.stream().filter(SeoMetadata::sitemap).toList();
    }

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
        return new SeoMetadata(path, "/", "SWAPI - The Star Wars API", HOME_DESCRIPTION,
                SeoMetadata.NOINDEX_FOLLOW, false, null, null);
    }

    public boolean isKnownDocumentPath(String rawPath) {
        String path = normalizePath(rawPath);
        return ROUTES.stream().anyMatch(route -> route.path().equals(path)) || resourceDetail(path) != null;
    }

    private static SeoMetadata route(String path, String title, String description, String frequency, String priority) {
        return new SeoMetadata(path, path, title, description, SeoMetadata.INDEX_FOLLOW, true, frequency, priority);
    }

    private static SeoMetadata resourceDetail(String path) {
        String[] parts = path.split("/");
        if (parts.length == 4 && "resource".equals(parts[1]) && !parts[3].isBlank()) {
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
                return new SeoMetadata(path, "/resource/" + parts[2], resourceTitle + " #" + parts[3] + " - SWAPI",
                        "Star Wars " + resourceTitle.toLowerCase() + " record #" + parts[3] + " from the SWAPI REST API.",
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
