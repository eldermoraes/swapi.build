package com.eldermoraes.seo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The shell and the preview image are prepared once per process, and on every
 * request in dev mode (#57). Plain JUnit: the sources count their reads, and
 * the expected documents are literals, worked out by hand from the rule the
 * site had before (clean the head, block before the closing head tag, beacon
 * before the closing body tag).
 */
class SiteShellTest {

    private static final String SHELL = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <title>old</title>
                <meta name="description" content="old" />
                <link rel="canonical" href="https://old.example/" />
              </head>
              <body>
                <main id="main-content" tabindex="-1"></main>
              </body>
            </html>
            """;
    private static final String BLOCK = "\n    <title>New</title>";
    private static final String BEACON = "<script defer src=\"https://b.example/b.js\"></script>";

    /** A source that counts its reads and answers what it holds now. */
    private static final class Counting<T> implements Supplier<Optional<T>> {
        private Optional<T> value;
        private int reads;

        Counting(T value) {
            this.value = Optional.ofNullable(value);
        }

        @Override
        public Optional<T> get() {
            reads++;
            return value;
        }
    }

    private static SiteShell shell(Supplier<Optional<String>> html, Optional<String> beacon, boolean devMode) {
        return new SiteShell(html, Optional::empty, beacon, devMode);
    }

    @Test
    void theHeadIsCleanedAndTheBlockGoesBeforeTheClosingHeadTag() {
        SiteShell shell = shell(new Counting<>(SHELL), Optional.empty(), false);
        assertEquals("""
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8" />
                 \s
                    <title>New</title>
                  </head>
                  <body>
                    <main id="main-content" tabindex="-1"></main>
                  </body>
                </html>
                """, shell.document(BLOCK).orElseThrow());
    }

    @Test
    void theBeaconGoesBeforeTheClosingBodyTag() {
        SiteShell shell = shell(new Counting<>(SHELL), Optional.of(BEACON), false);
        assertEquals("""
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8" />
                 \s
                    <title>New</title>
                  </head>
                  <body>
                    <main id="main-content" tabindex="-1"></main>
                    <script defer src="https://b.example/b.js"></script>
                  </body>
                </html>
                """, shell.document(BLOCK).orElseThrow());
    }

    // The block is text, never a regex replacement: a title may carry $ and \.
    @Test
    void aBlockWithDollarsAndBackslashesIsWrittenAsItIs() {
        SiteShell shell = shell(new Counting<>("<head></head><body></body>"), Optional.empty(), false);
        assertEquals("<head>\n    <title>$1 \\ $0</title>\n  </head><body></body>",
                shell.document("\n    <title>$1 \\ $0</title>").orElseThrow());
    }

    // As before: the tags are found in any case and written back in lower case.
    @Test
    void theClosingTagsAreFoundInAnyCase() {
        SiteShell shell = shell(new Counting<>("<HEAD></HEAD><BODY></BODY>"), Optional.of(BEACON), false);
        assertEquals("<HEAD>" + BLOCK + "\n  </head><BODY>  " + BEACON + "\n  </body>",
                shell.document(BLOCK).orElseThrow());
    }

    // As before: without a closing head tag the block has nowhere to go, and
    // without a closing body tag neither has the beacon.
    @Test
    void aShellWithoutClosingTagsIsServedAsItIs() {
        assertEquals("<p>bare</p>",
                shell(new Counting<>("<p>bare</p>"), Optional.of(BEACON), false).document(BLOCK).orElseThrow());
        assertEquals("<head>" + BLOCK + "\n  </head>",
                shell(new Counting<>("<head></head>"), Optional.of(BEACON), false).document(BLOCK).orElseThrow());
        assertEquals("<body>  " + BEACON + "\n  </body>",
                shell(new Counting<>("<body></body>"), Optional.of(BEACON), false).document(BLOCK).orElseThrow());
    }

    @Test
    void outsideDevModeTheShellIsReadOnce() {
        Counting<String> source = new Counting<>(SHELL);
        SiteShell shell = shell(source, Optional.of(BEACON), false);
        String first = shell.document(BLOCK).orElseThrow();
        source.value = Optional.of("<head></head>");
        assertEquals(first, shell.document(BLOCK).orElseThrow());
        assertEquals(first.replace("New", "Other"), shell.document("\n    <title>Other</title>").orElseThrow());
        assertEquals(1, source.reads);
    }

    @Test
    void inDevModeAChangedShellIsPickedUpOnTheNextRequest() {
        Counting<String> source = new Counting<>("<head></head><p>one</p>");
        SiteShell shell = shell(source, Optional.empty(), true);
        assertEquals("<head>" + BLOCK + "\n  </head><p>one</p>", shell.document(BLOCK).orElseThrow());
        source.value = Optional.of("<head></head><p>two</p>");
        assertEquals("<head>" + BLOCK + "\n  </head><p>two</p>", shell.document(BLOCK).orElseThrow());
        assertEquals(2, source.reads);
    }

    // A build without the shell passes the request on, as before, and the shell
    // is looked for again: "missing" is never what is remembered.
    @Test
    void aMissingShellIsNotRemembered() {
        Counting<String> source = new Counting<>(null);
        SiteShell shell = shell(source, Optional.empty(), false);
        assertEquals(Optional.empty(), shell.document(BLOCK));
        source.value = Optional.of("<head></head>");
        assertEquals(Optional.of("<head>" + BLOCK + "\n  </head>"), shell.document(BLOCK));
        assertEquals(Optional.of("<head>" + BLOCK + "\n  </head>"), shell.document(BLOCK));
        assertEquals(2, source.reads);
    }

    @Test
    void outsideDevModeThePreviewImageIsReadOnceAndIsOneBuffer() {
        byte[] png = "not really a png".getBytes(StandardCharsets.UTF_8);
        Counting<byte[]> source = new Counting<>(png);
        SiteShell shell = new SiteShell(Optional::empty, source, Optional.empty(), false);
        var first = shell.previewImage().orElseThrow();
        assertArrayEquals(png, first.getBytes());
        assertSame(first, shell.previewImage().orElseThrow());
        assertEquals(1, source.reads);
    }

    @Test
    void inDevModeThePreviewImageIsReadOnEveryRequest() {
        Counting<byte[]> source = new Counting<>(new byte[]{1});
        SiteShell shell = new SiteShell(Optional::empty, source, Optional.empty(), true);
        assertArrayEquals(new byte[]{1}, shell.previewImage().orElseThrow().getBytes());
        source.value = Optional.of(new byte[]{2});
        assertArrayEquals(new byte[]{2}, shell.previewImage().orElseThrow().getBytes());
    }

    @Test
    void aMissingPreviewImageIsNotRemembered() {
        Counting<byte[]> source = new Counting<>(null);
        SiteShell shell = new SiteShell(Optional::empty, source, Optional.empty(), false);
        assertEquals(Optional.empty(), shell.previewImage());
        source.value = Optional.of(new byte[]{3});
        assertArrayEquals(new byte[]{3}, shell.previewImage().orElseThrow().getBytes());
    }
}
