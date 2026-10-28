package com.eldermoraes.seo.md;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The converter of the Markdown copies (2.14.0): the subset of HTML the
 * templates of templates/ssr/ use, one case per rule. Plain JUnit.
 */
class MarkdownFromHtmlTest {

    private static final String ROOT = "https://preview.example";

    /** /docs and /resource/people/1 have a copy; nothing else does. */
    private static final Function<String, Optional<String>> COPIES = path -> switch (path) {
        case "/" -> Optional.of("/index.html.md");
        case "/docs", "/resource/people/1" -> Optional.of(path + ".md");
        default -> Optional.empty();
    };

    private static String md(String html) {
        return MarkdownFromHtml.convert(html, ROOT, COPIES);
    }

    @Test
    void headingsKeepTheirLevel() {
        assertEquals("# One\n\n## Two\n\n### Three\n\n#### Four\n",
                md("<h1>One</h1><h2>Two</h2><h3>Three</h3><h4>Four</h4>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"div", "section", "details", "summary", "form", "header"})
    void blockElementsBreakTheText(String tag) {
        assertEquals("before\n\ninside\n\nafter\n", md("before<" + tag + ">inside</" + tag + ">after"));
    }

    @Test
    void paragraphsAreSeparatedByABlankLine() {
        assertEquals("one\n\ntwo\n", md("<p>one</p><p>two</p>"));
    }

    @Test
    void strongEmAndBr() {
        assertEquals("a **b** *c*\\\nd\n", md("<p>a <strong>b</strong> <em>c</em><br>d</p>"));
    }

    @Test
    void relativeLinksBecomeAbsoluteOnTheGivenRoot() {
        assertEquals("[Films](https://preview.example/resource/films)\n", md("<p><a href=\"/resource/films\">Films</a></p>"));
    }

    @Test
    void linksToPagesWithACopyPointToTheCopy() {
        assertEquals("[Docs](https://preview.example/docs.md) [Luke](https://preview.example/resource/people/1.md) "
                        + "[Home](https://preview.example/index.html.md)\n",
                md("<p><a href=\"/docs\">Docs</a> <a href=\"/resource/people/1\">Luke</a> <a href=\"/\">Home</a></p>"));
    }

    // 2.15.0 (review of slice G2): a copy has no ids, so a link to a fragment of a
    // page with a copy points to the copy without it, keeping the query; a page
    // with no copy keeps its fragment (the HTML has the id).
    @Test
    void aFragmentLinkToACopyDropsTheFragment() {
        assertEquals("[Comparison](https://preview.example/docs.md) [Search](https://preview.example/docs.md?q=1) "
                        + "[Films](https://preview.example/resource/films#top)\n",
                md("<p><a href=\"/docs#compare\">Comparison</a> <a href=\"/docs?q=1#search\">Search</a> "
                        + "<a href=\"/resource/films#top\">Films</a></p>"));
    }

    @Test
    void externalLinksStayAsTheyAre() {
        assertEquals("[Quarkus](https://quarkus.io)\n",
                md("<p><a href=\"https://quarkus.io\" target=\"_blank\" rel=\"noopener\">Quarkus</a></p>"));
    }

    @Test
    void anAbsoluteLinkOnTheSameRootIsInternal() {
        assertEquals("[Docs](https://preview.example/docs.md)\n", md("<p><a href=\"https://preview.example/docs\">Docs</a></p>"));
    }

    @Test
    void aGroupOfLinksBecomesAList() {
        // The home's call-to-action row and its index of the six types: links
        // side by side in a box, which would otherwise run into one line.
        assertEquals("- [Get started](https://preview.example/docs.md)\n- [WebMCP](https://preview.example/docs/webmcp)\n",
                md("<div><a href=\"/docs\">Get started</a><a href=\"/docs/webmcp\">WebMCP</a></div>"));
    }

    @Test
    void listsAndNestedLists() {
        assertEquals("- one\n- two\n  - inner\n\n1. first\n2. second\n",
                md("<ul><li>one</li><li>two<ul><li>inner</li></ul></li></ul><ol><li>first</li><li>second</li></ol>"));
    }

    @Test
    void tableBecomesAGfmTable() {
        assertEquals("| Tool | Returns |\n| --- | --- |\n| `sw_get` | One entity |\n",
                md("<table><thead><tr><th>Tool</th><th>Returns</th></tr></thead>"
                        + "<tbody><tr><td><code>sw_get</code></td><td>One entity</td></tr></tbody></table>"));
    }

    @Test
    void tableWithHeaderCellsInTheFirstRowIsAGfmTable() {
        assertEquals("| A | B |\n| --- | --- |\n| 1 | 2 |\n",
                md("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"));
    }

    @Test
    void rowHeaderTableBecomesFieldValue() {
        assertEquals("| Field | Value |\n| --- | --- |\n| Director | George Lucas |\n| Episode | 4 |\n",
                md("<table><tbody><tr><th scope=\"row\">Director</th><td>George Lucas</td></tr>"
                        + "<tr><th scope=\"row\">Episode</th><td>4</td></tr></tbody></table>"));
    }

    @Test
    void pipeAndNewlineInACellAreEscaped() {
        assertEquals("| Field | Value |\n| --- | --- |\n| a\\|b | one<br>two |\n",
                md("<table><tr><th scope=\"row\">a|b</th><td>one<br>two</td></tr></table>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bash", "js", "json", "python", "toml"})
    void preBecomesAFenceWithTheLanguageOfItsClass(String lang) {
        assertEquals("```" + lang + "\nline 1\n  line 2\n```\n",
                md("<pre class=\"code-pre " + lang + "\">line 1\n  line 2</pre>"));
    }

    @Test
    void plainPreHasNoLanguage() {
        assertEquals("```\nhttps://preview.example/mcp\n```\n", md("<pre class=\"code-pre plain\">https://preview.example/mcp</pre>"));
    }

    @Test
    void anUnknownClassIsNoLanguage() {
        assertEquals("```\nx\n```\n", md("<pre class=\"code-pre http\">x</pre>"));
    }

    @Test
    void resultBodyIsJson() {
        assertEquals("```json\n{\"a\": 1}\n```\n", md("<pre class=\"result-body\">{&quot;a&quot;: 1}</pre>"));
    }

    @Test
    void emptyPreIsDropped() {
        assertEquals("text\n", md("<p>text</p><pre class=\"sw-term-out\" aria-live=\"polite\"></pre>"));
    }

    @Test
    void fenceIsLongerThanAnyBacktickRunInside() {
        assertEquals("`````\na ```` b\n`````\n", md("<pre>a ```` b</pre>"));
    }

    @Test
    void preKeepsMarkdownSpecialsAsTheyAre() {
        assertEquals("```bash\nsw_get *x* [y] <z>\n```\n", md("<pre class=\"bash\">sw_get *x* [y] &lt;z&gt;</pre>"));
    }

    @Test
    void inlineCode() {
        assertEquals("call `sw_get` now\n", md("<p>call <code>sw_get</code> now</p>"));
    }

    @Test
    void inlineCodeWithABacktick() {
        assertEquals("a `` x`y `` b\n", md("<p>a <code>x`y</code> b</p>"));
    }

    @Test
    void buttonsSvgAndAriaHiddenAreDropped() {
        assertEquals("kept\n", md("<div><button>Copy</button><svg><text>icon</text></svg>"
                + "<span aria-hidden=\"true\">→</span>kept<script>x()</script><style>p{}</style></div>"));
    }

    @Test
    void srOnlyLabelIsDropped() {
        assertEquals("", md("<div><label class=\"sr-only\" for=\"i\">API endpoint path</label><input id=\"i\"></div>"));
    }

    @Test
    void terminalHeadIsDropped() {
        assertEquals("", md("<div class=\"sw-term-head\"><span>GALACTIC TERMINAL</span><span>● LIVE</span></div>"));
    }

    @Test
    void resultHeaderIsDroppedButTheJsonStays() {
        assertEquals("```json\n{}\n```\n", md("<div class=\"result-panel\"><div class=\"result-header\">"
                + "<span>GET /api/films/1 <span>200</span></span></div><pre class=\"result-body\">{}</pre></div>"));
    }

    @Test
    void dataMdSkipIsDropped() {
        assertEquals("Title\n", md("<div><div data-md=\"skip\">⚡</div>Title</div>"));
    }

    @Test
    void entitiesAreDecoded() {
        assertEquals("← People & more\n", md("<p>&larr; People &amp; more</p>"));
    }

    @Test
    void markdownSpecialsInTextAreEscaped() {
        assertEquals("a \\* b \\_ c \\` d \\[e\\] \\<f\\> g \\\\ h\n", md("<p>a * b _ c ` d [e] &lt;f&gt; g \\ h</p>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "# not a heading | \\# not a heading",
            "- not a list | \\- not a list",
            "+ not a list | \\+ not a list",
            "1977. A year | 1977\\. A year",
    })
    void lineStartMarkersAreEscaped(String text, String expected) {
        assertEquals(expected + "\n", md("<p>" + text + "</p>"));
    }

    @Test
    void aQuoteMarkerAtLineStartIsEscapedByTheTextRule() {
        assertEquals("\\> quoted\n", md("<p>&gt; quoted</p>"));
    }

    @Test
    void whitespaceCollapsesOutsidePre() {
        assertEquals("a b c\n", md("<p>  a\n      b \t c  </p>"));
    }

    @Test
    void unknownTagKeepsOnlyItsText() {
        assertEquals("one two\n", md("<p><span class=\"x\">one</span> <mark>two</mark></p>"));
    }

    @Test
    void textThatLooksLikeHtmlStaysText() {
        String out = md("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
        assertEquals("\\<script\\>alert(1)\\</script\\>\n", out);
        assertFalse(out.contains("<script>"));
    }

    @Test
    void theMainWrapperIsNotInTheCopy() {
        String out = md("<main id=\"main-content\" data-ssr-page=\"about\"><h1>About</h1></main>");
        assertEquals("# About\n", out);
        assertFalse(out.contains("<main"));
    }

    @Test
    void theLanguagesAreAClosedSet() {
        assertEquals(Set.of("bash", "js", "json", "python", "toml"), MarkdownFromHtml.LANGUAGES);
    }

    @Test
    void escapeIsUsableForNamesOutsideAPage() {
        assertTrue(MarkdownFromHtml.escape("R2-D2 [astromech]").contains("\\[astromech\\]"));
    }
}
