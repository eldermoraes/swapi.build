package com.eldermoraes.seo.ssr;

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
    public static final Set<String> PAGES = Set.of("home", "docs", "mcp", "about");

    @Inject
    OpenApiDocs openApi;

    @Inject
    SeoMetadataRegistry metadata;

    // /docs has no host in it (its paths are relative): drawn once and reused.
    private volatile String docs;

    public String home() {
        return SsrTemplates.home(HomeView.of(metadata.site())).render();
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

    public String about() {
        return SsrTemplates.about().render();
    }
}
