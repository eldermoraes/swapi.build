package com.eldermoraes.seo.ssr;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /docs is drawn on the server from the app's own OpenAPI document (slice E,
 * 2.11.0), read once at startup through an internal Quarkus API that falls back
 * to an empty document instead of failing. This proves it read the real one.
 */
@QuarkusTest
class OpenApiDocsTest {

    @Inject
    OpenApiDocs docs;

    @Test
    void theDocumentReadAtStartupIsTheOneServedAtOpenapiJson() {
        JsonObject served = read(given().accept("application/json").when().get("/openapi.json").then()
                .statusCode(200).extract().asString());
        assertTrue(docs.document().isPresent(), "OpenApiDocs found no document");
        assertEquals(served, docs.document().get());
    }

    @Test
    void thePathsAreNotEmpty() {
        assertFalse(docs.document().orElseThrow().getJsonObject("paths").isEmpty());
    }

    @Test
    void everyGetOperationHasABlock() {
        JsonObject served = read(given().when().get("/openapi.json").then().extract().asString());
        TreeSet<String> gets = new TreeSet<>();
        for (Map.Entry<String, JsonValue> path : served.getJsonObject("paths").entrySet()) {
            if (path.getValue().asJsonObject().containsKey("get")) {
                gets.add(path.getKey());
            }
        }
        assertEquals(gets, new TreeSet<>(order()));
    }

    // TypeScript localeCompare put /api/people/{id} before /api/people/random; a
    // plain String.compareTo would not ({ is 123, r is 114). docs-order-2.10.0.txt
    // is the order the client drew in 2.10.0, taken from its jar with Playwright.
    @Test
    void theOrderIsTheOneOf2100() throws IOException {
        List<String> before = Files.readAllLines(Path.of("src/test/resources/ssr/docs-order-2.10.0.txt")).stream()
                .filter(line -> !line.isBlank()).toList();
        assertEquals(before, order());
    }

    @Test
    void rootComesFirst() {
        assertEquals("Root", docs.view().orElseThrow().tags().get(0).name());
    }

    private List<String> order() {
        return docs.view().orElseThrow().tags().stream()
                .flatMap(tag -> tag.operations().stream())
                .map(DocsView.Operation::path)
                .toList();
    }

    private static JsonObject read(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
