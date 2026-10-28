package com.eldermoraes.seo.md;

import com.eldermoraes.seo.KnownEntities;
import com.eldermoraes.seo.LlmsTxt;
import com.eldermoraes.seo.SeoMetadataRegistry;
import com.eldermoraes.seo.SeoSiteFile;
import com.eldermoraes.seo.ssr.ServerRendering;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The Markdown copies (slice G1, 2.14.0) and /llms-full.txt.
 *
 * A page has a copy when the server draws its {@code <main>}: the fixed pages
 * marked ssr in seo/site.json and every record address that exists, with the id
 * in its canonical form. The copy lives at the page's address plus .md; the
 * home's is /index.html.md (llmstxt.org). The copy is the drawn {@code <main>}
 * converted (MarkdownFromHtml): no template of its own, one source for the text.
 *
 * An empty render (a failed one, already logged by ServerRendering, or /docs
 * without the OpenAPI document) or a converter that throws (logged here) is no
 * copy: the caller answers 503, never an empty Markdown. /llms-full.txt is
 * empty as a whole when one of its copies is.
 *
 * Cache: the copies of the fixed pages and /llms-full.txt, per site root, in an
 * LRU of {@link #ROOTS} roots (the host comes from each request; in production
 * there is one root). Record copies are not cached (about 260 per root, each
 * small). The content only changes in a release. A failure is never cached.
 */
@ApplicationScoped
public class MarkdownCopies {

    /** The address of the home's copy. */
    public static final String HOME_COPY = "/index.html.md";
    /** The key of /llms-full.txt in the cache. */
    public static final String LLMS_FULL = "/llms-full.txt";
    /** How many site roots the cache keeps. */
    static final int ROOTS = 4;

    private static final String SUFFIX = ".md";
    private static final Logger LOG = Logger.getLogger(MarkdownCopies.class);

    @Inject
    ServerRendering rendering;

    @Inject
    SeoMetadataRegistry metadata;

    @Inject
    KnownEntities known;

    private final Map<String, Map<String, String>> cache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
            return size() > ROOTS;
        }
    };

    /** The address of the copy of a page (/docs -> /docs.md, / -> /index.html.md); empty when it has none. */
    public Optional<String> copyPathOf(String page) {
        if ("/".equals(page)) {
            return pageName("/").filter(rendering::isServerRendered).map(name -> HOME_COPY);
        }
        if (pageName(page).filter(rendering::isServerRendered).isPresent()) {
            return Optional.of(page + SUFFIX);
        }
        return recordOf(page).filter(record -> known.exists(record[0], record[1])).map(record -> page + SUFFIX);
    }

    /**
     * The page whose copy the raw path asks for, or empty: exactly /index.html.md
     * is the home; otherwise the path ends in .md and, without those three
     * characters, is exactly a page with a copy, not / and without a trailing
     * slash. Nothing is normalized: /.md, /docs/.md, /index.md, /docs.md/,
     * /DOCS.MD and /docs%2Emd are not copies.
     */
    public Optional<String> pageOfCopy(String rawPath) {
        if (HOME_COPY.equals(rawPath)) {
            return copyPathOf("/").map(copy -> "/");
        }
        if (rawPath == null || !rawPath.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        String page = rawPath.substring(0, rawPath.length() - SUFFIX.length());
        if (page.isEmpty() || "/".equals(page) || page.endsWith("/")) {
            return Optional.empty();
        }
        return copyPathOf(page).filter(rawPath::equals).map(copy -> page);
    }

    /**
     * The copy of a page with a copy: the line "> HTML version: <URL>", a blank
     * line and the Markdown of its {@code <main>}. Empty when the render is
     * empty or the conversion failed.
     *
     * @param siteRoot the root of this request, without a trailing slash
     */
    public Optional<String> copy(String page, String siteRoot) {
        if (recordOf(page).isPresent()) {
            return build(page, siteRoot);
        }
        return cached(siteRoot, page, () -> build(page, siteRoot));
    }

    /**
     * The summary of llms.txt, the copy of every fixed page with a copy (in the
     * order of seo/site.json), separated by "---", and the index of every record
     * (its copy and its JSON). Empty when a copy is unavailable.
     */
    public Optional<String> llmsFull(String siteRoot) {
        return cached(siteRoot, LLMS_FULL, () -> buildLlmsFull(siteRoot));
    }

    /** The seam of the converter (a test makes it throw). */
    protected String convert(String main, String siteRoot) {
        return MarkdownFromHtml.convert(main, siteRoot, this::copyPathOf);
    }

    int cachedRoots() {
        synchronized (cache) {
            return cache.size();
        }
    }

    boolean isCached(String siteRoot, String key) {
        synchronized (cache) {
            Map<String, String> entries = cache.get(siteRoot);
            return entries != null && entries.containsKey(key);
        }
    }

    private Optional<String> build(String page, String siteRoot) {
        Optional<String[]> record = recordOf(page);
        Optional<String> main = record.isPresent()
                ? rendering.record(record.get()[0], record.get()[1], siteRoot + "/api", siteRoot)
                : pageName(page).flatMap(name -> rendering.main(name, siteRoot));
        if (main.isEmpty()) {
            return Optional.empty();
        }
        String markdown;
        try {
            markdown = convert(main.get(), siteRoot);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Markdown copy of %s failed: answering 503", page);
            return Optional.empty();
        }
        if (markdown.isBlank()) {
            return Optional.empty();
        }
        return Optional.of("> HTML version: " + siteRoot + page + "\n\n" + markdown);
    }

    private Optional<String> buildLlmsFull(String siteRoot) {
        StringBuilder out = new StringBuilder(LlmsTxt.summary(siteRoot));
        for (SeoSiteFile.Page page : metadata.site().pages()) {
            if (copyPathOf(page.path()).isEmpty()) {
                continue;
            }
            Optional<String> copy = copy(page.path(), siteRoot);
            if (copy.isEmpty()) {
                return Optional.empty();
            }
            out.append("\n---\n\n").append(copy.get());
        }
        out.append("\n---\n\n## Records\n\nEvery record has a Markdown copy and its JSON on the API.\n");
        for (SeoSiteFile.ResourceType type : metadata.site().resources()) {
            out.append("\n### ").append(type.label()).append("\n\n");
            for (int id : known.ids(type.type())) {
                String name = known.name(type.type(), String.valueOf(id)).orElse(type.type() + "/" + id);
                out.append("- [").append(MarkdownFromHtml.escape(name)).append("](")
                        .append(siteRoot).append("/resource/").append(type.type()).append('/').append(id).append(SUFFIX)
                        .append(") — ").append(siteRoot).append("/api/").append(type.type()).append('/').append(id)
                        .append('\n');
            }
        }
        return Optional.of(out.toString());
    }

    private Optional<String> cached(String siteRoot, String key, Supplier<Optional<String>> build) {
        synchronized (cache) {
            Map<String, String> entries = cache.get(siteRoot);
            if (entries != null && entries.containsKey(key)) {
                return Optional.of(entries.get(key));
            }
        }
        Optional<String> built = build.get();
        built.ifPresent(value -> {
            synchronized (cache) {
                cache.computeIfAbsent(siteRoot, root -> new ConcurrentHashMap<>()).putIfAbsent(key, value);
            }
        });
        return built;
    }

    private Optional<String> pageName(String path) {
        return metadata.site().pages().stream()
                .filter(page -> page.path().equals(path))
                .map(SeoSiteFile.Page::page)
                .findFirst();
    }

    /** The type and id of a record address (/resource/{type}/{id}); empty for anything else. */
    private static Optional<String[]> recordOf(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length == 4 && parts[0].isEmpty() && "resource".equals(parts[1]) && !parts[3].isEmpty()) {
            return Optional.of(new String[]{parts[2], parts[3]});
        }
        return Optional.empty();
    }
}
