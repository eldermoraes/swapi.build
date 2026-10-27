package com.eldermoraes.seo;

import com.eldermoraes.seo.ssr.ServerRendering;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SeoRoutes {

    private static final String CACHE_CONTROL = "public, max-age=300, s-maxage=86400";
    private static final String OG_IMAGE = "/og-image.png";
    private static final String OG_IMAGE_ALT = "swapi.build — Star Wars API and MCP server in a Holonet terminal style";
    private static final Pattern EXISTING_SEO_TAG = Pattern.compile(
            "(?is)\\s*<title>.*?</title>|" +
            "\\s*<meta\\s+name=\\\"(?:description|robots|twitter:[^\\\"]+)\\\"[^>]*>|" +
            "\\s*<meta\\s+property=\\\"og:[^\\\"]+\\\"[^>]*>|" +
            "\\s*<link\\s+rel=\\\"canonical\\\"[^>]*>|" +
            "\\s*<script\\s+type=\\\"application/ld\\+json\\\"[^>]*>.*?</script>");

    @Inject SeoMetadataRegistry metadata;
    @Inject PublicSiteUrlResolver urls;
    @Inject WebAnalytics analytics;
    @Inject ServerRendering rendering;

    /**
     * The router of the site (2.9.1; Quinoa's SPA routing is off, QuinoaConfigTest).
     * In this order, for GET and HEAD, and never by the Accept header:
     * <ol>
     * <li>the SEO files (robots.txt, sitemap.xml, llms.txt, og-image.png);</li>
     * <li>/index.html: 301 to /, with the query string;</li>
     * <li>the backend (/api, /mcp, /openapi.json): passed on;</li>
     * <li>a page that exists, asked with a trailing slash: 301 to the bare path
     * (2.10.0), relative, with the query string;</li>
     * <li>a page that exists: 200 with its document, which carries the {@code <main>}
     * drawn on the server when seo/site.json marks the page ssr (2.11.0,
     * ServerRendering);</li>
     * <li>anything else under /resource/, even with a dot: 404 with the document;</li>
     * <li>a file candidate (a dot in the last segment, or /assets/, /.well-known/,
     * /@, /q/): passed on to the static handler, or to the framework 404, which
     * leaves with no-cache;</li>
     * <li>everything else: 404 with the document.</li>
     * </ol>
     * Pages are matched on the raw path (rc.request().path()), not on
     * rc.normalizedPath(), which percent-decodes: the browser keeps /resource/people/%31
     * as it is in location.pathname, so server and client agree it is not a page.
     */
    @RouteFilter(100)
    void route(RoutingContext rc) {
        if (!HttpMethod.GET.equals(rc.request().method()) && !HttpMethod.HEAD.equals(rc.request().method())) {
            rc.next();
            return;
        }

        String path = withoutTrailingSlash(rc.normalizedPath());
        switch (path) {
            case "/robots.txt" -> robots(rc);
            case "/sitemap.xml" -> sitemap(rc);
            case "/llms.txt" -> llms(rc);
            case OG_IMAGE -> ogImage(rc);
            case "/index.html" -> redirectHome(rc);
            default -> {
                String raw = rawPath(rc);
                String page = withoutTrailingSlash(raw);
                Optional<String> bare;
                if (isBackend(path)) {
                    // /api keeps its own 404 policy (edge-cacheable, CacheControlFilter);
                    // an unknown /mcp/... path is a framework 404 like a missing file.
                    if (path.startsWith("/mcp/")) {
                        noCacheOn404(rc);
                    }
                    rc.next();
                } else if ((bare = barePage(raw)).isPresent()) {
                    redirect(rc, bare.get());
                } else if (metadata.isKnownDocumentPath(page)) {
                    document(rc, page);
                } else if (path.startsWith("/resource/")) {
                    notFound(rc, page);
                } else if (isFileCandidate(path)) {
                    noCacheOn404(rc);
                    rc.next();
                } else {
                    notFound(rc, page);
                }
            }
        }
    }

    /**
     * Relative Location on purpose: it does not depend on the request host, so
     * there is nothing to poison. max-age=300, so after a rollback no browser
     * keeps the redirect for longer.
     */
    private void redirectHome(RoutingContext rc) {
        redirect(rc, "/");
    }

    private void redirect(RoutingContext rc, String location) {
        String query = rc.request().query();
        rc.response()
                .setStatusCode(301)
                .putHeader(HttpHeaders.LOCATION, query == null || query.isEmpty() ? location : location + "?" + query)
                .putHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .putHeader(HttpHeaders.VARY, "Origin")
                .end();
    }

    /**
     * The bare path of a page that exists, when the raw path asks for it with one
     * or more trailing slashes (/docs/ -> /docs, one hop). Empty for anything
     * else: an unknown path keeps its 404, and a path that only looks like a page
     * after the slashes go (//docs) is never a Location, so no protocol-relative
     * redirect can come out of here.
     */
    private Optional<String> barePage(String raw) {
        if (raw.length() <= 1 || !raw.endsWith("/")) {
            return Optional.empty();
        }
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == '/') {
            end--;
        }
        String bare = raw.substring(0, end);
        if (bare.isEmpty() || !bare.startsWith("/") || bare.startsWith("//") || !metadata.isKnownDocumentPath(bare)) {
            return Optional.empty();
        }
        return Optional.of(bare);
    }

    /**
     * A 404 page: the shell with a reduced head (title, description, robots
     * noindex and the swapi:status marker the client reads), and no canonical,
     * Open Graph, Twitter Card or JSON-LD, so it carries no URL built from the
     * host and reflects nothing of the path. no-cache replaces whatever the spa
     * or assets filters stamped: the path may start to exist in a release.
     */
    private void notFound(RoutingContext rc, String path) {
        Optional<String> shell = readIndexHtml();
        if (shell.isEmpty()) {
            noCacheOn404(rc);
            rc.next();
            return;
        }
        SeoMetadata route = metadata.notFound(path);
        rc.response()
                .setStatusCode(404)
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .putHeader(HttpHeaders.VARY, "Origin")
                .end(inject(shell.get(), notFoundBlock(route)));
    }

    /** Every 404 the app answers leaves with no-cache, never with the assets' immutable year. */
    private static void noCacheOn404(RoutingContext rc) {
        rc.addHeadersEndHandler(v -> {
            if (rc.response().getStatusCode() == 404) {
                rc.response().headers().set(HttpHeaders.CACHE_CONTROL, "no-cache");
            }
        });
    }

    private void robots(RoutingContext rc) {
        String body = "User-agent: *\n"
                + "Allow: /\n"
                + "Sitemap: " + urls.absoluteUrl(rc, "/sitemap.xml") + "\n";
        text(rc, "text/plain; charset=utf-8", body);
    }

    /**
     * llmstxt.org file for agents. Since 2.11.0 /docs and /docs/mcp come with
     * their content in the HTML (ServerRendering), and the file still carries the
     * essentials itself (how to connect, the list format, the id trap): an agent
     * that reads only this file gets them in one short request, without parsing a
     * page. Only {base} is replaced, with the root of this request; {resource},
     * {id} and {text} are literal URL parameters.
     */
    private static final String LLMS_TXT = """
            # swapi.build

            > Free, read-only Star Wars API (a SWAPI alternative) with a REST JSON interface and a
            > remote MCP server (Streamable HTTP). Data covers the six films of Episodes I-VI:
            > people, films, planets, species, starships and vehicles. No API key, no sign-up.

            - Connect: `claude mcp add --transport http swapi-build {base}/mcp`; any other MCP client:
              Streamable HTTP URL `{base}/mcp`, no auth. Tools: sw_search, sw_get, sw_list, sw_random.
            - REST: `{base}/api/{resource}`, `{base}/api/{resource}/{id}`, `{base}/api/{resource}/random`
              and `{base}/api/{resource}?search={text}`; resources: people, films, planets, species,
              starships, vehicles.
            - Lists and searches return a plain JSON array of entities (no count/next/results
              pagination, unlike swapi.dev).
            - Ids are record ids, not episode numbers: `{base}/api/films/1` is A New Hope (Episode IV).
            - Successful GETs return 200 with JSON; an unknown id returns 404.
            - Relations (homeworld, films, characters...) are absolute URLs on the same API.

            ## Docs

            - [OpenAPI contract]({base}/openapi.json): machine-readable, always matches the deployed version
            - [REST documentation]({base}/docs): endpoints with live examples
            - [MCP server guide]({base}/docs/mcp): setup for Claude Code, Claude Desktop, OpenAI Codex, GitHub Copilot and IBM Bob
            - [WebMCP guide]({base}/docs/webmcp): experimental browser tools

            ## Examples

            - [Luke Skywalker]({base}/api/people/1)
            - [A New Hope]({base}/api/films/1)
            - [Search people by name]({base}/api/people?search=sky)

            ## Optional

            - [About]({base}/about)
            - [Usage stats]({base}/stats), also as JSON at {base}/api/stats
            - [Source code](https://github.com/eldermoraes/swapi.build) (Apache 2.0)
            """;

    private void llms(RoutingContext rc) {
        String root = urls.absoluteUrl(rc, "/");
        String base = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        text(rc, "text/plain; charset=utf-8", LLMS_TXT.replace("{base}", base));
    }

    /**
     * Pages, lists and the records of the indexable types (SeoMetadataRegistry).
     * Never a lastmod: not the build date, not the deploy date. It enters only the
     * day there is a real date of content change per address, and there is none.
     */
    private void sitemap(RoutingContext rc) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (SeoMetadata route : metadata.sitemapRoutes()) {
            xml.append("  <url>\n")
                    .append("    <loc>").append(xml(urls.absoluteUrl(rc, route.canonicalPath()))).append("</loc>\n")
                    .append("    <changefreq>").append(route.changeFrequency()).append("</changefreq>\n")
                    .append("    <priority>").append(route.priority()).append("</priority>\n")
                    .append("  </url>\n");
        }
        xml.append("</urlset>\n");
        text(rc, "application/xml; charset=utf-8", xml.toString());
    }

    private void document(RoutingContext rc, String path) {
        Optional<String> shell = readIndexHtml();
        if (shell.isEmpty()) {
            rc.next();
            return;
        }
        SeoMetadata route = metadata.forPath(path);
        String html = inject(shell.get(), seoBlock(rc, route));
        // The <main> of a page marked ssr (2.11.0). After the head cleanup, so its
        // regex never reaches the content, and a literal replace: the content has
        // $ and \ (code samples) that a regex replacement would read. A failed
        // render leaves the empty <main> (ServerRendering logs it).
        Optional<String> main = pageName(path).flatMap(page -> rendering.main(page, urls.siteRoot(rc)));
        if (main.isPresent()) {
            html = html.replace(ServerRendering.EMPTY_MAIN, main.get());
        }
        rc.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .end(html);
    }

    private void ogImage(RoutingContext rc) {
        Optional<byte[]> image = readBytes("META-INF/resources/og-image.png")
                .or(() -> readBytes("og-image.png"))
                .or(() -> readFile("src/main/webui/public/og-image.png"));
        if (image.isEmpty()) {
            rc.next();
            return;
        }
        rc.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "image/png")
                .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .end(Buffer.buffer(image.get()));
    }

    // Vary: Origin because CORS echoes the request Origin and these files carry s-maxage.
    private void text(RoutingContext rc, String contentType, String body) {
        rc.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, contentType)
                .putHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .putHeader(HttpHeaders.VARY, "Origin")
                .end(body);
    }

    // The head block and the beacon. The <main> of an ssr page goes in after this
    // (document()), so the head cleanup never reaches the content.
    private String inject(String html, String block) {
        String cleaned = EXISTING_SEO_TAG.matcher(html).replaceAll("");
        String withSeo = cleaned.replaceFirst("(?i)</head>", Matcher.quoteReplacement(block + "\n  </head>"));
        // The analytics beacon goes before </body>, where Cloudflare recommends it.
        return analytics.beacon()
                .map(beacon -> withSeo.replaceFirst("(?i)</body>",
                        Matcher.quoteReplacement("  " + beacon + "\n  </body>")))
                .orElse(withSeo);
    }

    private String seoBlock(RoutingContext rc, SeoMetadata route) {
        String canonical = urls.absoluteUrl(rc, route.canonicalPath());
        String image = urls.absoluteUrl(rc, OG_IMAGE);
        String title = html(route.title());
        String description = html(route.description());
        String json = jsonLd(rc, route, canonical, image);
        return "\n    <title>" + title + "</title>\n"
                + "    <meta name=\"description\" content=\"" + description + "\" />\n"
                + "    <meta name=\"robots\" content=\"" + html(route.robots()) + "\" />\n"
                + "    <link rel=\"canonical\" href=\"" + html(canonical) + "\" />\n"
                + "    <meta property=\"og:type\" content=\"website\" />\n"
                + "    <meta property=\"og:site_name\" content=\"swapi.build\" />\n"
                + "    <meta property=\"og:locale\" content=\"en_US\" />\n"
                + "    <meta property=\"og:title\" content=\"" + title + "\" />\n"
                + "    <meta property=\"og:description\" content=\"" + description + "\" />\n"
                + "    <meta property=\"og:url\" content=\"" + html(canonical) + "\" />\n"
                + "    <meta property=\"og:image\" content=\"" + html(image) + "\" />\n"
                + "    <meta property=\"og:image:type\" content=\"image/png\" />\n"
                + "    <meta property=\"og:image:width\" content=\"1200\" />\n"
                + "    <meta property=\"og:image:height\" content=\"630\" />\n"
                + "    <meta property=\"og:image:alt\" content=\"" + html(OG_IMAGE_ALT) + "\" />\n"
                + "    <meta name=\"twitter:card\" content=\"summary_large_image\" />\n"
                + "    <meta name=\"twitter:title\" content=\"" + title + "\" />\n"
                + "    <meta name=\"twitter:description\" content=\"" + description + "\" />\n"
                + "    <meta name=\"twitter:image\" content=\"" + html(image) + "\" />\n"
                + "    <meta name=\"twitter:image:alt\" content=\"" + html(OG_IMAGE_ALT) + "\" />\n"
                + "    <script type=\"application/ld+json\">" + json + "</script>";
    }

    private static String notFoundBlock(SeoMetadata route) {
        return "\n    <title>" + html(route.title()) + "</title>\n"
                + "    <meta name=\"description\" content=\"" + html(route.description()) + "\" />\n"
                + "    <meta name=\"robots\" content=\"" + html(route.robots()) + "\" />\n"
                + "    <meta name=\"swapi:status\" content=\"404\" />";
    }

    // JsonLd (2.11.0): JSON-P and the script escape; the graph per document is described there.
    private String jsonLd(RoutingContext rc, SeoMetadata route, String canonical, String image) {
        return JsonLd.document(metadata.site(), path -> urls.absoluteUrl(rc, path), route, canonical, image,
                metadata.home().description());
    }

    /** The page name of a fixed page of seo/site.json; empty for lists and record addresses. */
    private Optional<String> pageName(String path) {
        return metadata.site().pages().stream()
                .filter(page -> page.path().equals(path))
                .map(SeoSiteFile.Page::page)
                .findFirst();
    }

    private static boolean isBackend(String path) {
        return path.equals("/api")
                || path.startsWith("/api/")
                || path.equals("/mcp")
                || path.startsWith("/mcp/")
                || path.equals("/openapi.json");
    }

    // A path that may be a file of the build or of public/: the static handler
    // serves it, or the framework answers 404. /@ is Vite's namespace in dev mode
    // and /q/ the Dev UI; in prod both are a framework 404. A future public/ file
    // without an extension would be treated as a page (docs/DEPLOY.md).
    private static boolean isFileCandidate(String path) {
        return lastSegment(path).contains(".")
                || path.startsWith("/assets/")
                || path.startsWith("/.well-known/")
                || path.startsWith("/@")
                || path.startsWith("/q/");
    }

    private static String rawPath(RoutingContext rc) {
        String raw = rc.request().path();
        return raw == null || raw.isEmpty() ? "/" : raw;
    }

    private static String withoutTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private Optional<String> readIndexHtml() {
        return readString("META-INF/resources/index.html")
                .or(() -> readString("index.html"))
                .or(() -> readFileString("src/main/webui/index.html"));
    }

    private Optional<String> readString(String resource) {
        return readBytes(resource).map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private Optional<byte[]> readBytes(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readFileString(String file) {
        return readFile(file).map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private Optional<byte[]> readFile(String file) {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String html(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String xml(String value) {
        return html(value).replace("'", "&apos;");
    }
}
