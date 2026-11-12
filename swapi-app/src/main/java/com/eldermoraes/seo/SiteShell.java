package com.eldermoraes.seo;

import io.quarkus.runtime.LaunchMode;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTML shell (webui/index.html) and the preview image, prepared once per
 * process (#57): both only change in a release, and every document and every
 * 404 page used to read the shell, clean its head and compile two patterns.
 *
 * Prepared means: the SEO tags the shell ships with are removed, the analytics
 * beacon is placed before the closing body tag (WebAnalytics: fixed for the
 * life of the process) and the result is cut at the closing head tag, where the
 * head block of each document goes. The bytes are the ones the site served when
 * it did all of this per request, block first and beacon after: a head block
 * never holds a closing body tag (its text is HTML-escaped and JsonLd escapes
 * the "&lt;/" of its script), and the beacon holds no closing head tag.
 *
 * In dev mode nothing is kept: the shell and the image are read on every
 * request, so a changed index.html shows up without a restart. A missing shell
 * or image is never kept either: the request is passed on, as before, and the
 * file is looked for again on the next one.
 */
@ApplicationScoped
public class SiteShell {

    private static final Pattern EXISTING_SEO_TAG = Pattern.compile(
            "(?is)\\s*<title>.*?</title>|" +
            "\\s*<meta\\s+name=\\\"(?:description|robots|twitter:[^\\\"]+)\\\"[^>]*>|" +
            "\\s*<meta\\s+property=\\\"og:[^\\\"]+\\\"[^>]*>|" +
            "\\s*<link\\s+rel=\\\"canonical\\\"[^>]*>|" +
            "\\s*<script\\s+type=\\\"application/ld\\+json\\\"[^>]*>.*?</script>");
    private static final Pattern HEAD_END = Pattern.compile("(?i)</head>");
    private static final Pattern BODY_END = Pattern.compile("(?i)</body>");

    /**
     * The shell cut where the head block goes; {@code afterBlock} is null when
     * the shell has no closing head tag (the block then has nowhere to go).
     */
    private record Prepared(String beforeBlock, String afterBlock) {

        String with(String block) {
            return afterBlock == null ? beforeBlock : beforeBlock + block + afterBlock;
        }
    }

    private final Supplier<Optional<String>> shellSource;
    private final Supplier<Optional<byte[]>> imageSource;
    private final Optional<String> beacon;
    private final boolean devMode;

    private volatile Prepared prepared;
    private volatile Buffer image;

    @Inject
    SiteShell(WebAnalytics analytics) {
        this(SiteShell::readIndexHtml, SiteShell::readPreviewImage, analytics.beacon(),
                LaunchMode.current() == LaunchMode.DEVELOPMENT);
    }

    /**
     * @param shellSource the shell as it is now, empty when the build has none
     * @param imageSource the preview image as it is now, empty when the build has none
     * @param beacon      the analytics script tag, empty when disabled
     * @param devMode     true to read both sources on every request
     */
    SiteShell(Supplier<Optional<String>> shellSource, Supplier<Optional<byte[]>> imageSource,
              Optional<String> beacon, boolean devMode) {
        this.shellSource = shellSource;
        this.imageSource = imageSource;
        this.beacon = beacon;
        this.devMode = devMode;
    }

    /**
     * The document of a page: the shell with this head block before its closing
     * head tag. Empty when the build has no shell.
     *
     * @param headBlock the tags of the page, text that is written as it is
     */
    public Optional<String> document(String headBlock) {
        Prepared shell = prepared;
        if (shell == null) {
            Optional<String> html = shellSource.get();
            if (html.isEmpty()) {
                return Optional.empty();
            }
            shell = prepare(html.get());
            if (!devMode) {
                prepared = shell;
            }
        }
        return Optional.of(shell.with(headBlock));
    }

    /**
     * The preview image (og-image.png). Outside dev mode it is one buffer for
     * every response: writing a Buffer does not consume it. Vert.x has no
     * read-only Buffer, so this stays inside the package: the one caller
     * (SeoRoutes) only writes it to the response, and nothing may change it.
     * Empty when the build has no image.
     */
    Optional<Buffer> previewImage() {
        Buffer buffer = image;
        if (buffer == null) {
            Optional<byte[]> bytes = imageSource.get();
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            buffer = Buffer.buffer(bytes.get());
            if (!devMode) {
                image = buffer;
            }
        }
        return Optional.of(buffer);
    }

    private Prepared prepare(String html) {
        String cleaned = EXISTING_SEO_TAG.matcher(html).replaceAll("");
        Matcher head = HEAD_END.matcher(cleaned);
        if (!head.find()) {
            return new Prepared(withBeacon(cleaned), null);
        }
        String before = cleaned.substring(0, head.start());
        String after = "\n  </head>" + cleaned.substring(head.end());
        // The first closing body tag of the document gets the beacon, as before:
        // it is looked for ahead of the head block first, and after it otherwise.
        String beforeWithBeacon = withBeacon(before);
        return beforeWithBeacon.equals(before)
                ? new Prepared(before, withBeacon(after))
                : new Prepared(beforeWithBeacon, after);
    }

    // The analytics beacon goes before </body>, where Cloudflare recommends it.
    private String withBeacon(String html) {
        return beacon
                .map(tag -> BODY_END.matcher(html).replaceFirst(Matcher.quoteReplacement("  " + tag + "\n  </body>")))
                .orElse(html);
    }

    private static Optional<String> readIndexHtml() {
        return readResource("META-INF/resources/index.html")
                .or(() -> readResource("index.html"))
                .or(() -> readFile("src/main/webui/index.html"))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    private static Optional<byte[]> readPreviewImage() {
        return readResource("META-INF/resources/og-image.png")
                .or(() -> readResource("og-image.png"))
                .or(() -> readFile("src/main/webui/public/og-image.png"));
    }

    private static Optional<byte[]> readResource(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> readFile(String file) {
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
}
