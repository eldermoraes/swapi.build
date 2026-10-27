package com.eldermoraes.seo.ssr;

import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JSON text of the record in the template of detail (2.12.0) is the one the
 * client produces, JSON.stringify(value, null, 2): two spaces, ": " after a key,
 * {} and [] when empty, and JavaScript's string escapes. Byte for byte, so the
 * client colours it without changing a character. The expected strings below
 * are what Node prints for the same values; the Vitest side checks the fixture
 * of /resource/films/1 against JSON.stringify itself (main-first-load.test.ts).
 */
class JsonTextTest {

    @Test
    void twoSpacesAndTheShapeOfJsonStringify() {
        assertEquals("""
                {
                  "a": 1,
                  "b": [
                    1,
                    "two"
                  ],
                  "c": {},
                  "d": [],
                  "e": {
                    "f": null,
                    "g": true,
                    "h": false
                  }
                }""", JsonText.pretty(read("{\"a\":1,\"b\":[1,\"two\"],\"c\":{},\"d\":[],\"e\":{\"f\":null,\"g\":true,\"h\":false}}")));
    }

    @Test
    void theStringEscapesOfJavaScript() {
        // JSON.stringify("q\"b\\s/\r\n\t\b\f\u0001é") === '"q\\"b\\\\s/\\r\\n\\t\\b\\f\\u0001é"'
        assertEquals("\"q\\\"b\\\\s/\\r\\n\\t\\b\\f\\u0001é\"",
                JsonText.pretty(Json.createValue("q\"b\\s/\r\n\t\b\f\u0001é")));
        assertEquals("\"\\u001f\"", JsonText.pretty(Json.createValue("\u001f")));
        assertEquals("\"<script>&amp;</script>\"", JsonText.pretty(Json.createValue("<script>&amp;</script>")));
    }

    @Test
    void integersAsJavaScriptPrintsThem() {
        assertEquals("[\n  4,\n  -7,\n  0\n]", JsonText.pretty(read("[4,-7,0]")));
    }

    @Test
    void theKeyOrderIsKept() {
        assertEquals("{\n  \"z\": 1,\n  \"a\": 2\n}", JsonText.pretty(read("{\"z\":1,\"a\":2}")));
    }

    private static JsonValue read(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readValue();
        }
    }
}
