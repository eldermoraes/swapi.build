package com.eldermoraes.seo.ssr;

import com.eldermoraes.RequestBaseUrl;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL base of a record drawn on the event loop (2.12.0, slice E2):
 * RecordViews sets RequestBaseUrl for its getters and puts back whatever was
 * there, and the JSON it carries is the API's, on the host of the request, with
 * the indentation the client writes.
 */
@QuarkusTest
class RecordViewsTest {

    private static final Pattern NESTED_KEY_AT_TOP = Pattern.compile("(?m)^ {4}\"[a-z_A-Z]+\": ");

    @Inject
    RecordViews views;

    @AfterEach
    void clear() {
        RequestBaseUrl.clear();
    }

    @Test
    void theBaseThatWasThereIsPutBack() {
        RequestBaseUrl.set("sentinel");
        views.build("people", "1", "https://a.example/api", "https://a.example").orElseThrow();
        assertEquals("sentinel", RequestBaseUrl.get());
    }

    @Test
    void anEmptyBaseStaysEmpty() {
        RequestBaseUrl.clear();
        views.build("people", "1", "https://a.example/api", "https://a.example").orElseThrow();
        assertNull(RequestBaseUrl.get());
    }

    @Test
    void theJsonIsOnTheBaseOfTheRequestAndNeverNull() {
        RequestBaseUrl.set("https://other.example/api");
        RecordView view = views.build("people", "1", "https://a.example/api", "https://a.example").orElseThrow();
        assertTrue(view.json().contains("\"url\": \"https://a.example/api/people/1\""), view.json());
        assertTrue(view.json().contains("\"homeworld\": \"https://a.example/api/planets/1\""), view.json());
        assertFalse(view.json().contains("null/"), view.json());
        assertFalse(view.json().contains("other.example"), view.json());
        assertEquals("curl https://a.example/api/people/1", view.curl());
        assertEquals("claude mcp add --transport http swapi-build https://a.example/mcp", view.mcpAdd());
        assertEquals("{\"resource\":\"PEOPLE\",\"id\":1}", view.mcpArgs());
    }

    @Test
    void theJsonIsTheApiBodyWithTwoSpaces() {
        RecordView view = views.build("people", "1", "https://a.example/api", "https://a.example").orElseThrow();
        String body = given().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "a.example")
                .when().get("/api/people/1").then().statusCode(200).extract().asString();
        assertEquals(read(body), read(view.json()));
        // Byte for byte what the client writes: JsonText of the API body itself.
        assertEquals(JsonText.pretty(read(body)), view.json());
        assertTrue(view.json().startsWith("{\n  \""), view.json());
        assertFalse(NESTED_KEY_AT_TOP.matcher(view.json()).find(), "a first-level key with four spaces: " + view.json());
    }

    @Test
    void theRelationsCarryTheNameOfEachRecord() {
        RecordView view = views.build("films", "1", "https://a.example/api", "https://a.example").orElseThrow();
        RecordView.Related characters = view.relations().getFirst();
        assertEquals("Characters", characters.label());
        assertEquals(new RecordView.Link("Luke Skywalker", "/resource/people/1", true), characters.links().getFirst());
        assertEquals("Films", view.typeLabel());
        assertEquals("A New Hope", view.name());
        assertTrue(view.openingCrawl().startsWith("It is a period of civil war."), view.openingCrawl());
    }

    @Test
    void aRelationToARecordThatDoesNotExistHasNoLink() {
        assertEquals(new RecordView.Link("https://a.example/api/planets/9999", "", false),
                views.link("planets", "https://a.example/api/planets/9999"));
        assertEquals(new RecordView.Link("https://a.example/api/planets/01", "", false),
                views.link("planets", "https://a.example/api/planets/01"));
        assertEquals(new RecordView.Link("Tatooine", "/resource/planets/1", true),
                views.link("planets", "https://a.example/api/planets/1"));
    }

    @Test
    void aRecordThatDoesNotExistHasNoView() {
        assertTrue(views.build("people", "17", "https://a.example/api", "https://a.example").isEmpty());
        assertTrue(views.build("people", "01", "https://a.example/api", "https://a.example").isEmpty());
        assertTrue(views.build("droids", "1", "https://a.example/api", "https://a.example").isEmpty());
    }

    private static JsonValue read(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readValue();
        }
    }
}
