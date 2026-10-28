package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.13.0 (slice F1): the optional fields argument of sw_list and sw_search
 * keeps, of each record, only the fields asked for plus url.
 */
@QuarkusTest
class SwapiFieldsTest {

    private static final List<String> TYPES = List.of("PEOPLE", "FILMS", "PLANETS", "SPECIES", "STARSHIPS", "VEHICLES");

    @Inject
    RecordExpansion expansion;

    @AfterAll
    static void closeClient() {
        SwapiExpandTest.closeClient();
    }

    private static ToolResponse list(String resource, List<String> fields) {
        Map<String, Object> args = new HashMap<>();
        args.put("resource", resource);
        if (fields != null) {
            args.put("fields", fields);
        }
        return SwapiExpandTest.call("sw_list", args);
    }

    private static String ok(ToolResponse r) {
        assertFalse(r.isError(), SwapiExpandTest.text(r));
        return SwapiExpandTest.text(r);
    }

    private static String error(ToolResponse r) {
        assertTrue(r.isError(), "expected a tool error, got " + SwapiExpandTest.text(r));
        return SwapiExpandTest.text(r);
    }

    private static List<String> keys(JsonObject o) {
        return List.copyOf(o.fieldNames());
    }

    @Test
    void fieldsIsAnOptionalArrayOfStrings() {
        SwapiExpandTest.client().when().toolsList(page -> {
            for (String name : List.of("sw_list", "sw_search")) {
                JsonObject schema = page.findByName(name).inputSchema();
                JsonObject fields = schema.getJsonObject("properties").getJsonObject("fields");
                assertEquals("array", fields.getString("type"), name);
                assertEquals("string", fields.getJsonObject("items").getString("type"), name);
                assertFalse(schema.getJsonArray("required").contains("fields"), name);
            }
            for (String name : List.of("sw_get", "sw_random")) {
                assertNull(page.findByName(name).inputSchema().getJsonObject("properties").getValue("fields"), name);
            }
        }).thenAssertResults();
    }

    @Test
    void listPeopleWithFieldsNameIsACompactIndex() {
        String text = ok(list("PEOPLE", List.of("name")));
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < 10_000, "sw_list PEOPLE fields [name]: " + bytes + " bytes");
        JsonArray people = new JsonArray(text);
        assertEquals(82, people.size());
        for (int i = 0; i < people.size(); i++) {
            assertEquals(List.of("name", "url"), keys(people.getJsonObject(i)));
        }
        assertEquals("Luke Skywalker", people.getJsonObject(0).getString("name"));
        assertFalse(text.contains("hair_color"));
        String full = ok(list("PEOPLE", null));
        System.out.println("sw_list PEOPLE: " + full.getBytes(StandardCharsets.UTF_8).length
                + " bytes; with fields [name]: " + bytes + " bytes");
    }

    @Test
    void fieldsAlwaysKeepUrl() {
        JsonArray people = new JsonArray(ok(list("PEOPLE", List.of("height"))));
        assertEquals(List.of("height", "url"), keys(people.getJsonObject(0)));
        JsonArray withUrl = new JsonArray(ok(list("PEOPLE", List.of("url"))));
        assertEquals(List.of("url"), keys(withUrl.getJsonObject(0)));
    }

    @Test
    void fieldsKeepTheRecordKeyOrder() {
        JsonArray full = new JsonArray(ok(list("PEOPLE", null)));
        JsonArray projected = new JsonArray(ok(list("PEOPLE", List.of("url", "name", "birth_year"))));
        List<String> expected = keys(full.getJsonObject(0)).stream()
                .filter(k -> Set.of("url", "name", "birth_year").contains(k)).toList();
        assertEquals(expected, keys(projected.getJsonObject(0)));
    }

    @Test
    void projectedValuesEqualTheFullRecord() {
        for (String type : TYPES) {
            JsonArray full = new JsonArray(ok(list(type, null)));
            List<String> some = new ArrayList<>(keys(full.getJsonObject(0))).subList(0, 3);
            JsonArray projected = new JsonArray(ok(list(type, some)));
            assertEquals(full.size(), projected.size(), type);
            for (int i = 0; i < full.size(); i++) {
                JsonObject record = full.getJsonObject(i);
                JsonObject cut = projected.getJsonObject(i);
                for (String key : cut.fieldNames()) {
                    assertEquals(record.getValue(key), cut.getValue(key), type + "[" + i + "]." + key);
                }
                assertEquals(record.getString("url"), cut.getString("url"));
            }
        }
    }

    @Test
    void emptyFieldsIsTheSameAsAbsent() {
        assertEquals(ok(list("FILMS", null)), ok(list("FILMS", List.of())));
        Map<String, Object> args = new HashMap<>();
        args.put("resource", "FILMS");
        args.put("fields", null);
        assertEquals(ok(list("FILMS", null)), ok(SwapiExpandTest.call("sw_list", args)));
    }

    @Test
    void duplicateFieldsCountOnce() {
        JsonArray films = new JsonArray(ok(list("FILMS", List.of("title", "title"))));
        assertEquals(List.of("title", "url"), keys(films.getJsonObject(0)));
    }

    @Test
    void searchWithFieldsAndNoMatchIsAnEmptyArray() {
        ToolResponse r = SwapiExpandTest.call("sw_search",
                Map.of("resource", "PEOPLE", "query", "zzzz", "fields", List.of("name")));
        assertEquals("[]", ok(r));
        ToolResponse sky = SwapiExpandTest.call("sw_search",
                Map.of("resource", "PEOPLE", "query", "sky", "fields", List.of("name")));
        JsonArray found = new JsonArray(ok(sky));
        assertEquals(3, found.size());
        assertEquals(List.of("name", "url"), keys(found.getJsonObject(0)));
    }

    @Test
    void unknownFieldIsAToolErrorListingTheValidFields() {
        String message = error(list("PEOPLE", List.of("nope")));
        assertTrue(message.startsWith("Unknown field 'nope' for people. Valid: "), message);
        assertTrue(message.contains("hair_color"), message);
        assertTrue(message.contains("url"), message);
    }

    @Test
    void moreThan24ItemsIsAToolError() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add("name");
        }
        assertTrue(error(list("PEOPLE", many)).contains("at most 24"));
        assertFalse(ok(list("PEOPLE", many.subList(0, 24))).isEmpty());
    }

    @Test
    void fieldLongerThan40IsAToolError() {
        String message = error(list("PEOPLE", List.of("n".repeat(41))));
        assertTrue(message.contains("40 characters"), message);
    }

    // The valid fields are the keys the REST API returns for each type.
    @Test
    void validFieldsMatchTheRestKeysOfEachType() {
        for (String type : TYPES) {
            String lower = type.toLowerCase();
            JsonArray rest = new JsonArray(given().accept("application/json").get("/api/" + lower)
                    .then().statusCode(200).extract().asString());
            Set<String> keys = new LinkedHashSet<>();
            for (int i = 0; i < rest.size(); i++) {
                keys.addAll(rest.getJsonObject(i).fieldNames());
            }
            assertEquals(keys, expansion.validFields(lower), type);
        }
    }

    @Test
    void starshipsAcceptMGLT() {
        JsonArray ships = new JsonArray(ok(list("STARSHIPS", List.of("MGLT", "name"))));
        assertEquals(List.of("MGLT", "name", "url"), keys(ships.getJsonObject(0)));
    }
}
