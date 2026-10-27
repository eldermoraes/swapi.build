package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.SeoMetadataRegistry;
import com.eldermoraes.seo.SeoSiteFile;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The <main> of the pages marked {@code ssr} in seo/site.json (slice E, 2.11.0),
 * drawn by the Qute templates of templates/ssr/: the one source of that markup.
 * The client adopts it on the first load when the marker matches the route and
 * fetches the document on later navigations (webui/src/server-main.ts).
 *
 * The marker is {@code data-ssr-page} with the page name, from the closed set
 * of seo/site.json: nothing of the raw path, the query or a header goes in it.
 *
 * Startup: a page marked ssr without a renderer stops the app with its name.
 * Request: an exception in a render never takes the site down; it is logged as
 * an ERROR with the page name and the page leaves as the shell without the
 * marker (the client shows its static error block, the content probe fails).
 * The render is synchronous and in memory, so it runs on the event loop.
 */
@Startup
@Singleton
public class ServerRendering {

    /** The literal of the shell (webui/index.html) that the rendered <main> replaces. */
    public static final String EMPTY_MAIN = "<main id=\"main-content\" tabindex=\"-1\"></main>";

    private static final Logger LOG = Logger.getLogger(ServerRendering.class);

    private final Set<String> ssr;
    private final SsrPages pages;

    @Inject
    ServerRendering(SeoMetadataRegistry metadata, SsrPages pages) {
        this(metadata.site(), pages);
    }

    public ServerRendering(SeoSiteFile site, SsrPages pages) {
        requireRenderers(site.pages(), SsrPages.PAGES);
        this.ssr = site.pages().stream().filter(SeoSiteFile.Page::ssr).map(SeoSiteFile.Page::page)
                .collect(Collectors.toUnmodifiableSet());
        this.pages = pages;
    }

    /** Throws, naming the page, when a page marked ssr has no renderer. */
    public static void requireRenderers(List<SeoSiteFile.Page> sitePages, Set<String> renderers) {
        for (SeoSiteFile.Page page : sitePages) {
            if (page.ssr() && !renderers.contains(page.page())) {
                throw new IllegalStateException(SeoSiteFile.RESOURCE + ": page \"" + page.page()
                        + "\" is marked ssr but has no server renderer (templates/ssr/" + page.page() + ".html, SsrPages)");
            }
        }
    }

    public boolean isServerRendered(String page) {
        return ssr.contains(page);
    }

    /**
     * The whole {@code <main>} with the marker, or empty: the page is not marked
     * ssr, its content is unavailable, or the render failed (logged).
     *
     * @param siteRoot the root of this request, for the one page that shows it (mcp)
     */
    public Optional<String> main(String page, String siteRoot) {
        if (!ssr.contains(page)) {
            return Optional.empty();
        }
        try {
            Optional<String> inner = switch (page) {
                case "home" -> Optional.of(pages.home());
                case "docs" -> pages.docs();
                case "mcp" -> Optional.of(pages.mcp(siteRoot));
                case "about" -> Optional.of(pages.about());
                default -> Optional.empty();
            };
            return inner.map(html -> "<main id=\"main-content\" tabindex=\"-1\" data-ssr-page=\"" + page + "\">"
                    + html + "</main>");
        } catch (RuntimeException e) {
            LOG.errorf(e, "Server render of page %s failed: serving the shell without content", page);
            return Optional.empty();
        }
    }
}
