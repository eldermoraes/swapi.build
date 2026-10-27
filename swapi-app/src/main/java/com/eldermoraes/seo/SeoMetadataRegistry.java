package com.eldermoraes.seo;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The head of every page, from seo/site.json (2.10.0: the one source, read by
 * the site too, webui/src/site.ts). Built at startup (@Startup): a missing or
 * invalid file stops the app with its message instead of serving untitled pages.
 *
 * Record addresses: one template per type. Whether a type is indexable is a rule
 * of the file (detail.indexable), never a list of records: a record added to the
 * data of an enabled type gets its title, index,follow, its own canonical and a
 * sitemap entry with no code change.
 */
@Startup
@ApplicationScoped
public class SeoMetadataRegistry {

    private final KnownEntities entities;
    private final SeoSiteFile site;
    private final List<SeoMetadata> routes;

    @Inject
    public SeoMetadataRegistry(KnownEntities entities) {
        this.entities = entities;
        this.site = SeoSiteFile.load();
        List<SeoMetadata> routes = new ArrayList<>();
        for (SeoSiteFile.Page page : site.pages()) {
            routes.add(route(page.path(), page.title(), page.description(), page.changefreq(), page.priority()));
        }
        for (SeoSiteFile.ResourceType resource : site.resources()) {
            SeoSiteFile.Listing list = resource.list();
            routes.add(route("/resource/" + resource.type(), list.title(), list.description(), list.changefreq(),
                    list.priority()));
        }
        this.routes = List.copyOf(routes);
    }

    /** seo/site.json as read at startup: the labels and the ssr pages (2.11.0). */
    public SeoSiteFile site() {
        return site;
    }

    /** The home page: the site as a whole (its URL and description describe the site in JSON-LD). */
    public SeoMetadata home() {
        return forPath("/");
    }

    /**
     * The pages, the lists and every record of a type marked indexable, walked
     * from the data. No lastmod: there is no real date of change per address (the
     * data has not changed since 2014), and the build or deploy date would be a lie.
     */
    public List<SeoMetadata> sitemapRoutes() {
        List<SeoMetadata> sitemap = new ArrayList<>(routes);
        for (SeoSiteFile.ResourceType resource : site.resources()) {
            if (resource.detail().indexable()) {
                for (int id : entities.ids(resource.type())) {
                    SeoMetadata record = resourceDetail("/resource/" + resource.type() + "/" + id);
                    if (record != null) {
                        sitemap.add(record);
                    }
                }
            }
        }
        return sitemap.stream().filter(SeoMetadata::sitemap).toList();
    }

    /**
     * The metadata of a page, or {@link #notFound} when the path is not a page.
     * The path is matched as given (callers pass the raw request path, not the
     * percent-decoded one), so /resource/people/%31 is not the page of id 1.
     */
    public SeoMetadata forPath(String rawPath) {
        String path = normalizePath(rawPath);
        for (SeoMetadata route : routes) {
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
        return routes.stream().anyMatch(route -> route.path().equals(path)) || resourceDetail(path) != null;
    }

    /** No canonical, noindex, out of the sitemap. Nothing of the path goes into the document. */
    public SeoMetadata notFound(String path) {
        return new SeoMetadata(path, null, site.notFound().title(), site.notFound().description(),
                SeoMetadata.NOINDEX, false, null, null);
    }

    private static SeoMetadata route(String path, String title, String description, String frequency, String priority) {
        return new SeoMetadata(path, path, title, description, SeoMetadata.INDEX_FOLLOW, true, frequency, priority);
    }

    /**
     * Only a record that exists, with its id in the canonical form (KnownEntities).
     * Title and description from the type's template, with the record's fields; a
     * record with an empty field gets the fallback. Indexable type: index,follow,
     * its own canonical and the sitemap. Otherwise: noindex,follow and the list's
     * canonical.
     */
    private SeoMetadata resourceDetail(String path) {
        String[] parts = path.split("/");
        if (parts.length != 4 || !"resource".equals(parts[1])) {
            return null;
        }
        Optional<SeoSiteFile.ResourceType> type = site.resource(parts[2]);
        Optional<Map<String, String>> fields = entities.fields(parts[2], parts[3]);
        if (type.isEmpty() || fields.isEmpty()) {
            return null;
        }
        SeoSiteFile.Detail detail = type.get().detail();
        Map<String, String> values = new HashMap<>(fields.get());
        values.put("id", parts[3]);
        String title = SeoSiteFile.render(detail.title(), values)
                .orElseGet(() -> SeoSiteFile.render(detail.fallbackTitle(), values).orElseThrow());
        String description = SeoSiteFile.render(detail.description(), values)
                .orElseGet(() -> SeoSiteFile.render(detail.fallbackDescription(), values).orElseThrow());
        if (detail.indexable()) {
            return new SeoMetadata(path, path, title, description, SeoMetadata.INDEX_FOLLOW, true,
                    detail.changefreq(), detail.priority());
        }
        return new SeoMetadata(path, "/resource/" + parts[2], title, description, SeoMetadata.NOINDEX_FOLLOW, false,
                null, null);
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
