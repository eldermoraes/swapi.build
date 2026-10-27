package com.eldermoraes.seo.ssr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard (slice E, 2.11.0): the server puts the <main> of a page in place of
 * this literal of the shell (ServerRendering.EMPTY_MAIN). If it changes, every
 * page would leave without content; the probe would catch it in production,
 * this catches it first.
 */
class SsrShellTest {

    private static final Path SHELL = Path.of("src/main/webui/index.html");

    @Test
    void theShellHasTheEmptyMainExactlyOnce() throws IOException {
        String html = Files.readString(SHELL);
        assertEquals(1, count(html, ServerRendering.EMPTY_MAIN), "index.html must keep " + ServerRendering.EMPTY_MAIN);
    }

    // The js class goes on <html> before the first paint, so the inactive MCP tab
    // panels hide under .js and never flash; without JavaScript all five show.
    @Test
    void theOnlyInlineScriptIsTheJsClassAndItComesBeforeTheCss() throws IOException {
        String html = Files.readString(SHELL);
        Matcher inline = Pattern.compile("(?is)<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>").matcher(html);
        assertTrue(inline.find(), "no inline script in index.html");
        assertEquals("document.documentElement.classList.add('js')", inline.group(1).trim().replaceAll(";$", ""));
        int script = inline.start();
        assertTrue(!inline.find(), "a second inline script in index.html");
        // Before any <link> (the fonts here, the page CSS Vite adds to the build).
        int css = html.indexOf("<link");
        int module = html.indexOf("<script type=\"module\"");
        assertTrue(css < 0 || script < css, "the js-class script must come before the CSS");
        assertTrue(module < 0 || script < module, "the js-class script must come before the module");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
