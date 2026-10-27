package com.eldermoraes.seo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server side of src/test/resources/seo/golden.json; seo-golden.test.ts is
 * the client side. Both check every case, so the two template engines (Java and
 * TS) cannot drift apart unseen (2.10.0, replaces seo-parity.test.ts).
 */
@QuarkusTest
class SeoGoldenTest {

    private static final String HOST = "preview.example";

    @Test
    void theServedHeadMatchesEveryGoldenCase() throws Exception {
        JsonObject golden;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("seo/golden.json");
             JsonReader reader = Json.createReader(in)) {
            golden = reader.readObject();
        }
        int checked = 0;
        for (JsonValue value : golden.getJsonArray("cases")) {
            JsonObject expected = value.asJsonObject();
            String path = expected.getString("path");
            boolean notFound = expected.isNull("canonicalPath");
            Response response = given()
                    .accept("text/html")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", HOST)
                    .redirects().follow(false)
                    .get(path);
            assertEquals(notFound ? 404 : 200, response.statusCode(), path);
            String html = response.body().asString();

            assertEquals(expected.getString("title"), unescape(first(html, "<title>([^<]*)</title>", path)), path);
            assertEquals(expected.getString("description"),
                    unescape(first(html, "<meta name=\"description\" content=\"([^\"]*)\" />", path)), path);
            assertEquals(expected.getString("robots"),
                    first(html, "<meta name=\"robots\" content=\"([^\"]*)\" />", path), path);
            if (notFound) {
                assertFalse(html.contains("rel=\"canonical\""), path);
            } else {
                String canonical = "https://" + HOST + expected.getString("canonicalPath");
                assertEquals(canonical, first(html, "<link rel=\"canonical\" href=\"([^\"]*)\" />", path), path);
                assertEquals(canonical, first(html, "<meta property=\"og:url\" content=\"([^\"]*)\" />", path), path);
                assertEquals(expected.getString("title"),
                        unescape(first(html, "<meta property=\"og:title\" content=\"([^\"]*)\" />", path)), path);
            }
            checked++;
        }
        assertTrue(checked >= 26, "golden.json has " + checked + " cases");
    }

    private static String first(String html, String regex, String path) {
        Matcher m = Pattern.compile(regex).matcher(html);
        assertTrue(m.find(), path + ": no match for " + regex);
        String value = m.group(1);
        assertNotNull(value);
        assertFalse(m.find(), path + ": more than one match for " + regex);
        return value;
    }

    private static String unescape(String value) {
        return value.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
