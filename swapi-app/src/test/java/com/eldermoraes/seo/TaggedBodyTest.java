package com.eldermoraes.seo;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The validator of a body and the If-None-Match grammar (#58; RFC 9110, 8.8.3
 * and 13.1.2). The HTTP behaviour is in ConditionalRequestsTest.
 */
class TaggedBodyTest {

    // printf 'hello' | openssl dgst -sha256 -binary | head -c 16 | base64, as base64url without padding.
    private static final String HELLO = "\"LPJNul-wow4m6Dsqxbning\"";

    private static final TaggedBody BODY = TaggedBody.of("hello");

    @Test
    void tagIsTheTruncatedSha256OfTheUtf8BytesInQuotes() {
        Assertions.assertEquals(HELLO, BODY.etag());
    }

    @Test
    void bufferHoldsTheUtf8BytesOfTheText() {
        TaggedBody body = TaggedBody.of("swapi — Æ");
        Assertions.assertEquals("swapi — Æ", body.text());
        Assertions.assertArrayEquals("swapi — Æ".getBytes(StandardCharsets.UTF_8), body.buffer().getBytes());
    }

    @Test
    void oneByteOfDifferenceIsAnotherTag() {
        Assertions.assertNotEquals(BODY.etag(), TaggedBody.of("hello ").etag());
        Assertions.assertEquals(BODY.etag(), TaggedBody.of("hello").etag());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"LPJNul-wow4m6Dsqxbning\"",
            "W/\"LPJNul-wow4m6Dsqxbning\"",
            "  \"LPJNul-wow4m6Dsqxbning\"\t",
            "\"other\", \"LPJNul-wow4m6Dsqxbning\"",
            "\"other\",W/\"LPJNul-wow4m6Dsqxbning\" , \"more\"",
            ", \"LPJNul-wow4m6Dsqxbning\",,",
            "\"with,comma\", \"LPJNul-wow4m6Dsqxbning\"",
            "*",
            " * ",
    })
    void namedBy(String header) {
        Assertions.assertTrue(BODY.isNamedBy(List.of(header)), header);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "\"other\"",
            "W/\"other\"",
            "LPJNul-wow4m6Dsqxbning",
            "\"LPJNul-wow4m6Dsqxbning",
            "LPJNul-wow4m6Dsqxbning\"",
            "w/\"LPJNul-wow4m6Dsqxbning\"",
            "\"LPJNul-wow4m6Dsqxbning\" \"other\"",
            "\"LPJNul-wow4m6Dsqxbning\", other",
            "\"xLPJNul-wow4m6Dsqxbning\"",
            "\"LPJNul-wow4m6Dsqxbning-zstd\"",
            "\"a,\"LPJNul-wow4m6Dsqxbning\"",
            "*, \"other\"",
            "\"*\"",
    })
    void notNamedBy(String header) {
        Assertions.assertFalse(BODY.isNamedBy(List.of(header)), header);
    }

    @Test
    void everyHeaderLineIsRead() {
        Assertions.assertTrue(BODY.isNamedBy(List.of("\"other\"", HELLO)));
        Assertions.assertTrue(BODY.isNamedBy(List.of("not a list", "W/" + HELLO)));
        Assertions.assertFalse(BODY.isNamedBy(List.of("\"other\"", "\"more\"")));
        Assertions.assertFalse(BODY.isNamedBy(List.of()));
    }
}
