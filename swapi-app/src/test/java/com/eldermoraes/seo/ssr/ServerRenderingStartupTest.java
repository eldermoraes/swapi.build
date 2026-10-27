package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.SeoSiteFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page marked ssr without a renderer is a configuration error: the app does
 * not start, and the message names the page (the same rule as an invalid
 * seo/site.json). The suite and the Native smoke catch it before any deploy.
 */
class ServerRenderingStartupTest {

    @Test
    void anSsrPageWithoutARendererStopsTheStartWithItsName() {
        List<SeoSiteFile.Page> pages = List.of(page("/", "home", true), page("/about", "about", true));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ServerRendering.requireRenderers(pages, Set.of("home")));
        assertTrue(e.getMessage().contains("about"), e.getMessage());
    }

    @Test
    void pagesNotMarkedSsrNeedNoRenderer() {
        List<SeoSiteFile.Page> pages = List.of(page("/", "home", true), page("/stats", "stats", false));
        assertDoesNotThrow(() -> ServerRendering.requireRenderers(pages, Set.of("home")));
    }

    @Test
    void theRealSiteFileHasARendererForEverySsrPage() {
        assertDoesNotThrow(() -> ServerRendering.requireRenderers(SeoSiteFile.load().pages(), SsrPages.PAGES));
    }

    private static SeoSiteFile.Page page(String path, String name, boolean ssr) {
        return new SeoSiteFile.Page(path, name, path.equals("/") ? null : name, ssr, name + " | swapi.build",
                "description", "monthly", "0.5");
    }
}
