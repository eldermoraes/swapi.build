package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.KnownEntities;
import com.eldermoraes.seo.SeoMetadataRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.Set;

/**
 * The inside of the <main> of each server-rendered page (slice E, 2.11.0).
 * ServerRendering wraps it with the marker and turns any exception into the
 * shell without content.
 */
@ApplicationScoped
public class SsrPages {

    /** The pages this class draws; every page marked ssr in seo/site.json must be one. */
    public static final Set<String> PAGES = Set.of("home", "docs", "mcp", "faq", "about");

    @Inject
    OpenApiDocs openApi;

    @Inject
    SeoMetadataRegistry metadata;

    // /docs has no host in it (its paths are relative): drawn once and reused.
    private volatile String docs;

    // Nor have the home and /about (#57): only seo/site.json and the template.
    private volatile String home;
    private volatile String about;

    public String home() {
        String html = home;
        if (html == null) {
            html = SsrTemplates.home(HomeView.of(metadata.site())).render();
            home = html;
        }
        return html;
    }

    /** Empty when the OpenAPI document could not be read at startup (OpenApiDocs). */
    public Optional<String> docs() {
        String html = docs;
        if (html == null) {
            Optional<DocsView> view = openApi.view();
            if (view.isEmpty()) {
                return Optional.empty();
            }
            html = SsrTemplates.docs(view.get()).render();
            docs = html;
        }
        return Optional.of(html);
    }

    /** @param siteRoot the root of this request (PublicSiteUrlResolver.siteRoot), for the endpoint */
    public String mcp(String siteRoot) {
        return SsrTemplates.mcp(McpGuides.view(siteRoot + "/mcp")).render();
    }

    @Inject
    FaqContent faqContent;

    @Inject
    KnownEntities known;

    // /faq has no host in it either (its links are paths or https URLs): drawn
    // once with the counts of the data and reused.
    private volatile String faq;

    /** The FAQ (2.15.0): the text of seo/faq.json with {count:type} filled from the data. */
    public String faq() {
        String html = faq;
        if (html == null) {
            html = SsrTemplates.faq(FaqView.of(faqContent.file(), type -> known.ids(type).size())).render();
            faq = html;
        }
        return html;
    }

    public String about() {
        String html = about;
        if (html == null) {
            html = SsrTemplates.about().render();
            about = html;
        }
        return html;
    }

    @Inject
    RecordViews records;

    /**
     * The template of detail (2.12.0) for a record that exists; empty otherwise.
     * RecordViews builds the view with the base of this request set and restored;
     * the render runs after, on Strings only.
     */
    public Optional<String> record(String type, String id, String apiBase, String siteRoot) {
        return records.build(type, id, apiBase, siteRoot).map(view -> SsrTemplates.record(view).render());
    }
}
