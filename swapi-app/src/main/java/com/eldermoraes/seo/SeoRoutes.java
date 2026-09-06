package com.eldermoraes.seo;

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
import java.util.regex.Pattern;

@ApplicationScoped
public class SeoRoutes {

    private static final String CACHE_CONTROL = "public, max-age=300, s-maxage=86400";
    private static final String OG_IMAGE = "/og-image.png";
    private static final String OG_IMAGE_ALT = "SWAPI — Star Wars API and MCP server in a Holonet terminal style";
    private static final Pattern EXISTING_SEO_TAG = Pattern.compile(
            "(?is)\\s*<title>.*?</title>|" +
            "\\s*<meta\\s+name=\\\"(?:description|robots|twitter:[^\\\"]+)\\\"[^>]*>|" +
            "\\s*<meta\\s+property=\\\"og:[^\\\"]+\\\"[^>]*>|" +
            "\\s*<link\\s+rel=\\\"canonical\\\"[^>]*>|" +
            "\\s*<script\\s+type=\\\"application/ld\\+json\\\"[^>]*>.*?</script>");

    @Inject SeoMetadataRegistry metadata;
    @Inject PublicSiteUrlResolver urls;

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
            case OG_IMAGE -> ogImage(rc);
            default -> {
                if (isDocumentRequest(rc, path)) {
                    document(rc, path);
                } else {
                    rc.next();
                }
            }
        }
    }

    private void robots(RoutingContext rc) {
        String body = "User-agent: *\n"
                + "Allow: /\n"
                + "Sitemap: " + urls.absoluteUrl(rc, "/sitemap.xml") + "\n";
        text(rc, "text/plain; charset=utf-8", body);
    }

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
        String html = injectSeo(shell.get(), rc, route);
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

    private void text(RoutingContext rc, String contentType, String body) {
        rc.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, contentType)
                .putHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .end(body);
    }

    private String injectSeo(String html, RoutingContext rc, SeoMetadata route) {
        String cleaned = EXISTING_SEO_TAG.matcher(html).replaceAll("");
        String block = seoBlock(rc, route);
        return cleaned.replaceFirst("(?i)</head>", block + "\n  </head>");
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
                + "    <meta property=\"og:site_name\" content=\"SWAPI\" />\n"
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

    private String jsonLd(RoutingContext rc, SeoMetadata route, String canonical, String image) {
        String home = urls.absoluteUrl(rc, "/");
        String docs = urls.absoluteUrl(rc, "/docs");
        String mcp = urls.absoluteUrl(rc, "/docs/mcp");
        String repo = "https://github.com/eldermoraes/swapi.build";
        String license = "https://www.apache.org/licenses/LICENSE-2.0";
        return "{"
                + "\"@context\":\"https://schema.org\","
                + "\"@graph\":["
                + "{\"@type\":\"WebSite\",\"name\":\"SWAPI\",\"url\":\"" + json(home) + "\",\"description\":\"" + json(route.description()) + "\",\"image\":\"" + json(image) + "\"},"
                + "{\"@type\":\"SoftwareApplication\",\"name\":\"SWAPI\",\"applicationCategory\":\"DeveloperApplication\",\"operatingSystem\":\"Any\",\"url\":\"" + json(canonical) + "\",\"description\":\"" + json(route.description()) + "\",\"image\":\"" + json(image) + "\",\"license\":\"" + license + "\",\"sameAs\":[\"" + repo + "\",\"" + json(docs) + "\",\"" + json(mcp) + "\"]}"
                + "]}";
    }

    private boolean isDocumentRequest(RoutingContext rc, String path) {
        if (excluded(path)) {
            return false;
        }
        // The SPA HTML is stored at the edge by path alone (quarkus.http.filter.spa)
        // and Accept is not part of the cache key, so a known route must answer the
        // same document to every client. Deciding by Accept here would let the first
        // non-HTML request after a deploy pin the wrong body for the whole deployment.
        if (metadata.isKnownDocumentPath(path)) {
            return true;
        }
        String accept = rc.request().getHeader(HttpHeaders.ACCEPT);
        boolean acceptsHtml = accept == null || accept.contains("text/html") || accept.contains("*/*");
        return acceptsHtml && !lastSegment(path).contains(".");
    }

    private boolean excluded(String path) {
        return path.startsWith("/api")
                || path.equals("/mcp")
                || path.startsWith("/mcp/")
                || path.equals("/openapi.json")
                || path.startsWith("/_vercel/")
                || path.startsWith("/assets/")
                || path.equals("/favicon.svg");
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

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("</", "<\\/");
    }
}
