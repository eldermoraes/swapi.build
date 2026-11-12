package com.eldermoraes.seo;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * A body of the site with its validator (#58): the text, the UTF-8 bytes that
 * go on the wire and the strong entity tag of those bytes.
 *
 * The tag is the SHA-256 of the bytes, cut to 128 bits, in base64url between
 * double quotes. It is of the bytes and of nothing else (no version, no route
 * name), so it changes exactly when the body does: with the content of a
 * release, with the site root of the request, which every body embeds, and
 * with the analytics beacon. Strong, because the application sends one
 * representation per address: no content negotiation and no compression.
 *
 * The digest runs once, here. A body that is kept (MarkdownCopies) is kept as
 * this value, so its tag is never computed again; a body composed per request
 * (a document, a record copy) is digested per request, in memory.
 */
public final class TaggedBody {

    private static final int TAG_BYTES = 16;

    private final String text;
    private final byte[] bytes;
    private final String etag;

    private TaggedBody(String text) {
        this.text = text;
        this.bytes = text.getBytes(StandardCharsets.UTF_8);
        this.etag = '"' + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOf(sha256(bytes), TAG_BYTES)) + '"';
    }

    public static TaggedBody of(String text) {
        return new TaggedBody(text);
    }

    public String text() {
        return text;
    }

    /** The entity tag as it goes in the ETag header, quotes included. */
    public String etag() {
        return etag;
    }

    /** The bytes the tag is of, in a buffer of its own for one response. */
    public Buffer buffer() {
        return Buffer.buffer(bytes);
    }

    /**
     * Whether an If-None-Match field names this body (RFC 9110, 13.1.2), so a
     * GET or HEAD is answered 304. The comparison is weak: the opaque tags are
     * equal, with or without W/ on the one received. "*" names any body. A line
     * that is not a list of entity tags names nothing.
     *
     * @param ifNoneMatch every line of the header, in the order received
     */
    public boolean isNamedBy(List<String> ifNoneMatch) {
        for (String line : ifNoneMatch) {
            if ("*".equals(line.trim()) || listHasTag(line)) {
                return true;
            }
        }
        return false;
    }

    // #entity-tag: elements separated by commas, empty ones allowed. Read with
    // the quotes in mind, since an opaque tag may hold a comma.
    private boolean listHasTag(String list) {
        boolean found = false;
        int at = 0;
        while (true) {
            at = skip(list, at, true);
            if (at >= list.length()) {
                return found;
            }
            if (list.startsWith("W/", at)) {
                at += 2;
            }
            int close = at < list.length() && list.charAt(at) == '"' ? list.indexOf('"', at + 1) : -1;
            if (close < 0) {
                return false;
            }
            found |= etag.equals(list.substring(at, close + 1));
            at = skip(list, close + 1, false);
            if (at < list.length() && list.charAt(at) != ',') {
                return false;
            }
        }
    }

    private static int skip(String list, int from, boolean commas) {
        int at = from;
        while (at < list.length()
                && (list.charAt(at) == ' ' || list.charAt(at) == '\t' || (commas && list.charAt(at) == ','))) {
            at++;
        }
        return at;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
