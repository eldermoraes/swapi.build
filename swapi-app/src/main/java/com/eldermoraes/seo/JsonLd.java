package com.eldermoraes.seo;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The JSON-LD of the head (2.11.0, slice E1), built with JSON-P, which escapes
 * quotes, backslashes and control characters; {@link #forScript} then keeps
 * the text from closing or diverting the {@code <script>}. Key order matters to
 * the tests that cut the SoftwareApplication up to its first closing brace, so
 * author comes after description and image.
 *
 * Graph: WebSite (+alternateName, the home description on every document, the
 * author), Person (the author), SoftwareApplication (the site, also on a record
 * address), a Dataset on the home only (no license: the data is not Apache 2.0)
 * and a BreadcrumbList on every page but the home, on the lists and, since
 * 2.12.0 (slice E2), on every record address (Home, the list, the record). The
 * 404 has no JSON-LD (SeoRoutes.notFoundBlock).
 */
public final class JsonLd {

    private static final String REPO = "https://github.com/eldermoraes/swapi.build";
    private static final String LICENSE = "https://www.apache.org/licenses/LICENSE-2.0";
    private static final String AUTHOR_NAME = "Elder Moraes";
    private static final String AUTHOR_URL = "https://www.linkedin.com/in/eldermoraes/";
    private static final String AUTHOR_GITHUB = "https://github.com/eldermoraes";

    private JsonLd() {
    }

    /**
     * @param url             absolute URL of a site path on the host of this request
     * @param route           the document's metadata
     * @param homeDescription the description of the home (WebSite and, on record addresses, the app)
     * @param recordName      the name (title of a film) of the record on a record address (2.12.0),
     *                        the last of its crumbs; empty elsewhere
     */
    static String document(SeoSiteFile site, UnaryOperator<String> url, SeoMetadata route, String canonical,
                           String image, String homeDescription, Optional<String> recordName) {
        String home = url.apply("/");
        String author = home + "#author";
        String path = route.path();
        String[] parts = path.split("/");
        boolean record = parts.length == 4 && "resource".equals(parts[1]);
        boolean listing = parts.length == 3 && "resource".equals(parts[1]);

        JsonArrayBuilder graph = Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("@type", "WebSite")
                        .add("name", "swapi.build")
                        .add("@id", home + "#website")
                        .add("alternateName", Json.createArrayBuilder().add("swapi build").add("swapi.build Star Wars API"))
                        .add("url", home)
                        .add("description", homeDescription)
                        .add("image", image)
                        .add("author", Json.createObjectBuilder().add("@id", author)))
                .add(Json.createObjectBuilder()
                        .add("@type", "Person")
                        .add("@id", author)
                        .add("name", AUTHOR_NAME)
                        .add("url", AUTHOR_URL)
                        .add("sameAs", Json.createArrayBuilder().add(AUTHOR_GITHUB)))
                // On a record address the application is still the site, never the
                // record (2.10.0): a film as the description of an app would mislead.
                .add(Json.createObjectBuilder()
                        .add("@type", "SoftwareApplication")
                        .add("name", "swapi.build")
                        .add("applicationCategory", "DeveloperApplication")
                        .add("operatingSystem", "Any")
                        .add("url", record ? home : canonical)
                        .add("description", record ? homeDescription : route.description())
                        .add("image", image)
                        .add("license", LICENSE)
                        .add("sameAs", Json.createArrayBuilder().add(REPO).add(url.apply("/docs")).add(url.apply("/docs/mcp")))
                        .add("author", Json.createObjectBuilder().add("@id", author)));

        if (path.equals("/")) {
            graph.add(dataset(site, url, home));
        } else if (listing) {
            site.resource(parts[2]).ifPresent(type -> graph.add(breadcrumbs(url,
                    new String[]{"Home", "/"}, new String[]{type.label(), path})));
        } else if (record) {
            // Slice E2 (2.12.0): Home, the list of the type, the record, on every
            // record address (the noindex of a type does not stop the markup).
            site.resource(parts[2]).ifPresent(type -> recordName.ifPresent(name -> graph.add(breadcrumbs(url,
                    new String[]{"Home", "/"}, new String[]{type.label(), "/resource/" + parts[2]},
                    new String[]{name, path}))));
        } else {
            site.pages().stream().filter(page -> page.path().equals(path)).findFirst().ifPresent(page -> {
                if (path.startsWith("/docs/")) {
                    String docs = site.pages().stream().filter(p -> p.path().equals("/docs"))
                            .map(SeoSiteFile.Page::label).findFirst().orElse("Documentation");
                    graph.add(breadcrumbs(url, new String[]{"Home", "/"}, new String[]{docs, "/docs"},
                            new String[]{page.label(), path}));
                } else {
                    graph.add(breadcrumbs(url, new String[]{"Home", "/"}, new String[]{page.label(), path}));
                }
            });
        }

        JsonObject document = Json.createObjectBuilder()
                .add("@context", "https://schema.org")
                .add("@graph", graph)
                .build();
        return forScript(document.toString());
    }

    /**
     * JSON made safe inside {@code <script>}: {@code </} becomes {@code <\/} (so
     * the text never closes the element) and {@code <!--} becomes
     * {@code <!--} (the HTML parser treats it specially in a script). Both
     * are still the same JSON.
     */
    static String forScript(String json) {
        return json.replace("</", "<\\/").replace("<!--", "\\u003C!--");
    }

    private static JsonObjectBuilder dataset(SeoSiteFile site, UnaryOperator<String> url, String home) {
        JsonArrayBuilder distribution = Json.createArrayBuilder();
        JsonArrayBuilder keywords = Json.createArrayBuilder().add("Star Wars").add("API").add("JSON");
        for (SeoSiteFile.ResourceType type : site.resources()) {
            keywords.add(type.label());
            distribution.add(Json.createObjectBuilder()
                    .add("@type", "DataDownload")
                    .add("name", type.label())
                    .add("encodingFormat", "application/json")
                    .add("contentUrl", url.apply("/api/" + type.type())));
        }
        return Json.createObjectBuilder()
                .add("@type", "Dataset")
                .add("name", "Star Wars data from Episodes I–VI")
                .add("description", "People, films, planets, species, starships and vehicles of the six Star Wars"
                        + " films of Episodes I–VI, with the links between them, as JSON from a free REST API and MCP"
                        + " server. No API key.")
                .add("url", home)
                .add("creator", Json.createObjectBuilder()
                        .add("@type", "Person")
                        .add("name", AUTHOR_NAME)
                        .add("url", AUTHOR_URL))
                .add("isBasedOn", "https://swapi.dev")
                .add("isAccessibleForFree", true)
                .add("keywords", keywords)
                .add("distribution", distribution);
    }

    private static JsonObjectBuilder breadcrumbs(UnaryOperator<String> url, String[]... crumbs) {
        JsonArrayBuilder items = Json.createArrayBuilder();
        for (int i = 0; i < crumbs.length; i++) {
            items.add(Json.createObjectBuilder()
                    .add("@type", "ListItem")
                    .add("position", i + 1)
                    .add("name", crumbs[i][0])
                    .add("item", url.apply(crumbs[i][1])));
        }
        return Json.createObjectBuilder()
                .add("@type", "BreadcrumbList")
                .add("itemListElement", items);
    }
}
