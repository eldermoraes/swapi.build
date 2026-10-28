package com.eldermoraes.seo.md;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown of a {@code <main>} the server already drew (2.14.0): the copy
 * has no template of its own, so the Qute templates stay the one source of the
 * text (CLAUDE.md, "Server-rendered pages have one source").
 *
 * Reads only HTML drawn by our templates, and only the subset they use:
 * headings h1-h4, paragraphs, strong/em/br, block boxes (div, section,
 * details, summary, form, header) as breaks, links, lists, tables (with a
 * header row, or with row headers only, which become "Field | Value"), pre as a
 * fenced block with the language of its class, and inline code. Any other tag
 * keeps only its text. Out of the copy: buttons, svg, script, style, inputs,
 * the visually hidden label, the terminal head, the result header of a record,
 * anything aria-hidden="true" and anything marked data-md="skip".
 *
 * A box whose only children are links (the call-to-action row and the index of
 * the home) becomes a list, one link per line.
 *
 * Pure Java on jsoup, no reflection, no resource file: the same in the native
 * image.
 */
public final class MarkdownFromHtml {

    /** The languages a pre may carry in its class; "plain" and anything else is no language. */
    public static final Set<String> LANGUAGES = Set.of("bash", "js", "json", "python", "toml");

    private static final Set<String> DROPPED_TAGS = Set.of(
            "button", "svg", "script", "style", "input", "select", "textarea", "template", "noscript");
    private static final Set<String> DROPPED_CLASSES = Set.of("sr-only", "sw-term-head", "result-header");
    private static final Set<String> BOXES = Set.of(
            "div", "section", "details", "summary", "form", "header", "main", "article", "aside", "nav", "footer");
    private static final Set<String> BLOCKS = Set.of(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "table", "pre");

    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\n\\r\\f]+");
    private static final Pattern LINE_START_NUMBER = Pattern.compile("^(\\d+)\\.(\\s|$)");
    private static final Pattern BACKTICKS = Pattern.compile("`+");

    /** A hard line break inside a paragraph (backslash at the end of the line). */
    private static final String HARD_BREAK = "\\\n";

    private final String root;
    private final Function<String, Optional<String>> copyOf;

    private MarkdownFromHtml(String root, Function<String, Optional<String>> copyOf) {
        this.root = root;
        this.copyOf = copyOf;
    }

    /**
     * @param html     the drawn {@code <main>} (or any fragment of our templates)
     * @param siteRoot the root of this request, without a trailing slash: relative
     *                 links resolve against it
     * @param copyOf   the copy of a page, by its path (/docs -> /docs.md): an
     *                 internal link to a page with a copy points to the copy
     * @return the Markdown, ending with one newline; empty when nothing is left
     */
    public static String convert(String html, String siteRoot, Function<String, Optional<String>> copyOf) {
        Element body = Jsoup.parseBodyFragment(html).body();
        List<String> blocks = new MarkdownFromHtml(siteRoot, copyOf).blocks(body);
        String out = String.join("\n\n", blocks).strip();
        return out.isEmpty() ? "" : out + "\n";
    }

    /** Text escaped for Markdown: \ * _ ` [ ] < and >. */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '*' || c == '_' || c == '`' || c == '[' || c == ']' || c == '<' || c == '>') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    // --- blocks ------------------------------------------------------------------

    private List<String> blocks(Element container) {
        List<String> out = new ArrayList<>();
        if (isLinkGroup(container)) {
            List<String> items = new ArrayList<>();
            for (Element link : container.children()) {
                if (!dropped(link)) {
                    items.add("- " + tidy(inline(link)));
                }
            }
            out.add(String.join("\n", items));
            return out;
        }
        StringBuilder text = new StringBuilder();
        for (Node node : container.childNodes()) {
            if (node instanceof Element element) {
                if (dropped(element)) {
                    continue;
                }
                if (isBlock(element)) {
                    flush(out, text);
                    out.addAll(block(element));
                    continue;
                }
                text.append(inline(element));
            } else if (node instanceof TextNode t) {
                text.append(escape(collapse(t.getWholeText())));
            }
        }
        flush(out, text);
        return out;
    }

    private List<String> block(Element element) {
        String tag = element.normalName();
        List<String> out = new ArrayList<>();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = tidy(inline(element)).replace(HARD_BREAK, " ");
                if (!text.isEmpty()) {
                    out.add("#".repeat(Math.min(tag.charAt(1) - '0', 6)) + " " + text);
                }
            }
            case "p" -> addIfPresent(out, paragraph(inline(element)));
            case "ul", "ol" -> addIfPresent(out, list(element, "ol".equals(tag)));
            case "table" -> addIfPresent(out, table(element));
            case "pre" -> addIfPresent(out, pre(element));
            default -> out.addAll(blocks(element));
        }
        return out;
    }

    private boolean isBlock(Element element) {
        String tag = element.normalName();
        if (BLOCKS.contains(tag) || BOXES.contains(tag)) {
            return true;
        }
        // An unknown tag that holds blocks is read as a box, so its blocks keep their breaks.
        for (Element child : element.children()) {
            if (!dropped(child) && isBlock(child)) {
                return true;
            }
        }
        return false;
    }

    /** A box whose only children are two or more links, with no text of its own between them. */
    private boolean isLinkGroup(Element container) {
        if (!BOXES.contains(container.normalName())) {
            return false;
        }
        int links = 0;
        for (Node node : container.childNodes()) {
            if (node instanceof TextNode t) {
                if (!t.isBlank()) {
                    return false;
                }
            } else if (node instanceof Element element) {
                if (dropped(element)) {
                    continue;
                }
                if (!"a".equals(element.normalName()) || !element.hasAttr("href")) {
                    return false;
                }
                links++;
            }
        }
        return links >= 2;
    }

    private static void flush(List<String> out, StringBuilder text) {
        addIfPresent(out, paragraph(text.toString()));
        text.setLength(0);
    }

    private static void addIfPresent(List<String> out, String block) {
        if (block != null && !block.isBlank()) {
            out.add(block);
        }
    }

    /** One paragraph: spaces tidied, and a marker of Markdown at the start of a line escaped. */
    private static String paragraph(String inline) {
        String text = tidy(inline);
        if (text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = escapeLineStart(lines[i]);
        }
        return String.join("\n", lines);
    }

    private static String escapeLineStart(String line) {
        if (line.startsWith("#") || line.startsWith("-") || line.startsWith("+") || line.startsWith(">")) {
            return "\\" + line;
        }
        Matcher number = LINE_START_NUMBER.matcher(line);
        if (number.find()) {
            return number.group(1) + "\\." + line.substring(number.group(1).length() + 1);
        }
        return line;
    }

    /** Runs of spaces become one, and no line starts or ends with a space. */
    private static String tidy(String inline) {
        String[] lines = inline.replaceAll(" {2,}", " ").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].strip();
        }
        return String.join("\n", lines).strip();
    }

    private String list(Element list, boolean ordered) {
        List<String> items = new ArrayList<>();
        int n = 1;
        for (Element li : list.children()) {
            if (!"li".equals(li.normalName()) || dropped(li)) {
                continue;
            }
            String marker = ordered ? (n++) + ". " : "- ";
            String body = String.join("\n", blocks(li));
            if (body.isBlank()) {
                continue;
            }
            String indent = " ".repeat(marker.length());
            items.add(marker + body.replace("\n", "\n" + indent).replaceAll("\n +\n", "\n\n"));
        }
        return String.join("\n", items);
    }

    private String table(Element table) {
        List<Element> rows = table.select("tr");
        rows.removeIf(row -> row.closest("table") != table || dropped(row));
        if (rows.isEmpty()) {
            return "";
        }
        List<List<String>> body = new ArrayList<>();
        List<String> header;
        Element first = rows.getFirst();
        boolean headerRow = first.parent() != null && "thead".equals(first.parent().normalName())
                || (!first.select("> th").isEmpty() && first.select("> td").isEmpty()
                        && first.select("> th[scope=row]").isEmpty());
        boolean rowHeaders = !headerRow && rows.stream().allMatch(row -> !row.select("> th[scope=row]").isEmpty());
        if (headerRow) {
            header = cells(first);
            rows = rows.subList(1, rows.size());
        } else if (rowHeaders) {
            header = List.of("Field", "Value");
        } else {
            header = cells(first);
            rows = rows.subList(1, rows.size());
        }
        for (Element row : rows) {
            body.add(cells(row));
        }
        int width = header.size();
        for (List<String> row : body) {
            width = Math.max(width, row.size());
        }
        StringBuilder out = new StringBuilder();
        out.append(row(header, width)).append('\n');
        out.append(row(java.util.Collections.nCopies(width, "---"), width));
        for (List<String> row : body) {
            out.append('\n').append(row(row, width));
        }
        return out.toString();
    }

    private List<String> cells(Element row) {
        List<String> out = new ArrayList<>();
        for (Element cell : row.children()) {
            String tag = cell.normalName();
            if ((!"th".equals(tag) && !"td".equals(tag)) || dropped(cell)) {
                continue;
            }
            String text = tidy(inline(cell))
                    .replace("|", "\\|")
                    .replace(HARD_BREAK, "<br>")
                    .replace("\n", "<br>");
            out.add(text);
        }
        return out;
    }

    private static String row(List<String> cells, int width) {
        StringBuilder out = new StringBuilder("|");
        for (int i = 0; i < width; i++) {
            out.append(' ').append(i < cells.size() ? cells.get(i) : "").append(" |");
        }
        return out.toString();
    }

    private static String pre(Element pre) {
        String content = pre.wholeText();
        if (content.startsWith("\n")) {
            content = content.substring(1);
        }
        content = content.stripTrailing();
        if (content.isBlank()) {
            return "";
        }
        String fence = "`".repeat(Math.max(3, longestBacktickRun(content) + 1));
        return fence + language(pre) + "\n" + content + "\n" + fence;
    }

    private static String language(Element pre) {
        Set<String> classes = pre.classNames();
        if (classes.contains("result-body")) {
            return "json";
        }
        for (String language : LANGUAGES) {
            if (classes.contains(language)) {
                return language;
            }
        }
        return "";
    }

    // --- inline ------------------------------------------------------------------

    private String inline(Element element) {
        if (dropped(element)) {
            return "";
        }
        return switch (element.normalName()) {
            case "strong", "b" -> wrap(children(element), "**");
            case "em", "i" -> wrap(children(element), "*");
            case "br" -> HARD_BREAK;
            case "code" -> code(element);
            case "a" -> link(element);
            default -> children(element);
        };
    }

    private String children(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof Element child) {
                out.append(inline(child));
            } else if (node instanceof TextNode t) {
                out.append(escape(collapse(t.getWholeText())));
            }
        }
        return out.toString();
    }

    /** The marks go around the text, and the spaces at its edges stay outside them. */
    private static String wrap(String text, String mark) {
        String inner = text.strip();
        if (inner.isEmpty()) {
            return text;
        }
        String before = text.startsWith(" ") ? " " : "";
        String after = text.endsWith(" ") ? " " : "";
        return before + mark + inner + mark + after;
    }

    private static String code(Element code) {
        String text = collapse(code.wholeText());
        if (text.isBlank()) {
            return "";
        }
        String ticks = "`".repeat(longestBacktickRun(text) + 1);
        boolean pad = text.startsWith("`") || text.endsWith("`") || ticks.length() > 1;
        return pad ? ticks + " " + text + " " + ticks : ticks + text + ticks;
    }

    private String link(Element a) {
        String text = children(a);
        if (!a.hasAttr("href")) {
            return text;
        }
        String url = url(a.attr("href"));
        String label = tidy(text).replace(HARD_BREAK, " ");
        if (label.isEmpty()) {
            label = escape(url);
        }
        String before = text.startsWith(" ") ? " " : "";
        String after = text.endsWith(" ") ? " " : "";
        return before + "[" + label + "](" + url + ")" + after;
    }

    /** Absolute on the root of the request; an internal page with a copy points to its copy. */
    private String url(String href) {
        String absolute;
        try {
            absolute = URI.create(root + "/").resolve(href.strip()).toString();
        } catch (IllegalArgumentException e) {
            absolute = href.strip();
        }
        String prefix = root + "/";
        if (absolute.startsWith(prefix) || absolute.equals(root)) {
            String rest = absolute.length() > root.length() ? absolute.substring(root.length()) : "/";
            int cut = indexOfAny(rest, '?', '#');
            String path = cut < 0 ? rest : rest.substring(0, cut);
            String tail = cut < 0 ? "" : rest.substring(cut);
            Optional<String> copy = copyOf.apply(path);
            if (copy.isPresent()) {
                absolute = root + copy.get() + tail;
            }
        }
        return absolute.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
    }

    // --- helpers -----------------------------------------------------------------

    private static boolean dropped(Element element) {
        if (DROPPED_TAGS.contains(element.normalName())) {
            return true;
        }
        if ("true".equals(element.attr("aria-hidden")) || "skip".equals(element.attr("data-md"))) {
            return true;
        }
        for (String name : element.classNames()) {
            if (DROPPED_CLASSES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** As the browser collapses: any run of white space is one space. */
    private static String collapse(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ");
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        Matcher m = BACKTICKS.matcher(text);
        while (m.find()) {
            longest = Math.max(longest, m.group().length());
        }
        return longest;
    }

    private static int indexOfAny(String text, char a, char b) {
        int i = text.indexOf(a);
        int j = text.indexOf(b);
        if (i < 0) {
            return j;
        }
        return j < 0 ? i : Math.min(i, j);
    }
}
