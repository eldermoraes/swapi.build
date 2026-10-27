package com.eldermoraes.mcp;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.people.People;
import com.eldermoraes.seo.KnownEntities;
import com.eldermoraes.seo.ssr.RecordFields;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.13.0 (slice F1): the optional expand argument of sw_get embeds, one level
 * deep, the records a relation points to, after the text of 2.12.1.
 */
@QuarkusTest
class SwapiExpandTest {

    private static final List<String> TYPES = List.of("PEOPLE", "FILMS", "PLANETS", "SPECIES", "STARSHIPS", "VEHICLES");

    static McpStreamableTestClient client;

    @Inject
    RecordExpansion expansion;

    @Inject
    KnownEntities known;

    @Inject
    jakarta.json.bind.Jsonb jsonb;

    static McpStreamableTestClient client() {
        if (client == null) {
            client = McpAssured.newConnectedStreamableClient();
        }
        return client;
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    static ToolResponse call(String tool, Map<String, Object> arguments) {
        AtomicReference<ToolResponse> response = new AtomicReference<>();
        client().when().toolsCall(tool).withArguments(arguments).withAssert(response::set).send().thenAssertResults();
        assertNotNull(response.get());
        return response.get();
    }

    static String text(ToolResponse r) {
        return r.content().get(0).asText().text();
    }

    private static String get(String resource, int id, List<String> expand) {
        Map<String, Object> args = new HashMap<>();
        args.put("resource", resource);
        args.put("id", id);
        if (expand != null) {
            args.put("expand", expand);
        }
        ToolResponse r = call("sw_get", args);
        assertFalse(r.isError(), text(r));
        return text(r);
    }

    private static String error(String resource, int id, List<String> expand) {
        ToolResponse r = call("sw_get", Map.of("resource", resource, "id", id, "expand", expand));
        assertTrue(r.isError(), "expected a tool error, got " + text(r));
        return text(r);
    }

    @Test
    void expandIsAnOptionalArrayOfStrings() {
        client().when().toolsList(page -> {
            JsonObject schema = page.findByName("sw_get").inputSchema();
            JsonObject expand = schema.getJsonObject("properties").getJsonObject("expand");
            assertNotNull(expand, schema.encode());
            assertEquals("array", expand.getString("type"));
            assertEquals("string", expand.getJsonObject("items").getString("type"));
            assertFalse(schema.getJsonArray("required").contains("expand"));
            for (String other : List.of("sw_list", "sw_random", "sw_search")) {
                assertNull(page.findByName(other).inputSchema().getJsonObject("properties").getValue("expand"), other);
            }
        }).thenAssertResults();
    }

    @Test
    void getWithExpandHomeworldEmbedsTatooine() {
        JsonObject luke = new JsonObject(get("PEOPLE", 1, List.of("homeworld")));
        assertEquals("Luke Skywalker", luke.getString("name"));
        JsonObject expanded = luke.getJsonObject("expanded");
        assertEquals(List.of("homeworld"), List.copyOf(expanded.fieldNames()));
        assertEquals("Tatooine", expanded.getJsonObject("homeworld").getString("name"));
        assertEquals(luke.getString("homeworld"), expanded.getJsonObject("homeworld").getString("url"));
    }

    // The 2.12.1 text stays as it is: only a final key is added.
    @Test
    void expandedTextStartsWithTheUnexpandedText() {
        for (String type : TYPES) {
            String plain = get(type, firstId(type), null);
            String expanded = get(type, firstId(type), List.of("*"));
            String head = plain.substring(0, plain.length() - 1);
            assertTrue(expanded.startsWith(head + ",\"expanded\":{"), type + ": " + expanded.substring(0, 200));
            assertTrue(expanded.endsWith("}}"), type);
        }
    }

    @Test
    void expandStarEmbedsEveryRelationOfEachType() {
        for (String type : TYPES) {
            JsonObject record = new JsonObject(get(type, firstId(type), List.of("*")));
            List<String> names = RecordFields.relations(type.toLowerCase()).stream().map(RecordFields.Relation::key).toList();
            assertEquals(names, List.copyOf(record.getJsonObject("expanded").fieldNames()), type);
        }
    }

    @Test
    void expandIsOneLevelDeep() {
        JsonObject film = new JsonObject(get("FILMS", 1, List.of("*")));
        JsonArray characters = film.getJsonObject("expanded").getJsonArray("characters");
        JsonObject luke = characters.getJsonObject(0);
        assertFalse(luke.containsKey("expanded"), luke.encode());
        assertTrue(luke.getString("homeworld").startsWith("http://localhost:8081/api/planets/"), luke.encode());
        assertTrue(luke.getValue("films") instanceof JsonArray a && a.getValue(0) instanceof String, luke.encode());
    }

    // Droid (species 2) has no homeworld: the key is there, with null.
    @Test
    void speciesWithoutHomeworldExpandsToNull() {
        JsonObject droid = new JsonObject(get("SPECIES", 2, List.of("homeworld")));
        assertEquals("Droid", droid.getString("name"));
        JsonObject expanded = droid.getJsonObject("expanded");
        assertTrue(expanded.containsKey("homeworld"), expanded.encode());
        assertNull(expanded.getValue("homeworld"));
    }

    // Test data with a URL to a record that does not exist: null in its
    // position, the array keeps the length of the URLs.
    @Test
    void danglingRelationIsNullInItsPosition() {
        People ghost = new People();
        ghost.setName("Ghost");
        ghost.setHomeworld("/planets/999");
        ghost.setFilms(List.of("/films/1", "/films/99", "/films/2"));
        ghost.setSpecies(List.of());
        ghost.setStarships(List.of());
        ghost.setUrl("/people/999");
        String previous = RequestBaseUrl.get();
        RequestBaseUrl.set("http://dangling.example/api");
        try {
            String plain = "{\"name\":\"Ghost\"}";
            JsonObject out = new JsonObject(expansion.expand("people", ghost, plain,
                    expansion.relations("people", List.of("homeworld", "films"))));
            JsonObject expanded = out.getJsonObject("expanded");
            assertNull(expanded.getValue("homeworld"));
            assertTrue(expanded.containsKey("homeworld"));
            JsonArray films = expanded.getJsonArray("films");
            assertEquals(3, films.size());
            assertEquals("A New Hope", films.getJsonObject(0).getString("title"));
            assertNull(films.getValue(1));
            assertEquals("The Empire Strikes Back", films.getJsonObject(2).getString("title"));
        } finally {
            if (previous == null) {
                RequestBaseUrl.clear();
            } else {
                RequestBaseUrl.set(previous);
            }
        }
    }

    @Test
    void expandedArraysHaveTheLengthOfTheUrls() {
        JsonObject film = new JsonObject(get("FILMS", 1, List.of("characters", "planets")));
        for (String relation : List.of("characters", "planets")) {
            JsonArray urls = film.getJsonArray(relation);
            JsonArray records = film.getJsonObject("expanded").getJsonArray(relation);
            assertEquals(urls.size(), records.size(), relation);
            for (int i = 0; i < urls.size(); i++) {
                assertEquals(urls.getString(i), records.getJsonObject(i).getString("url"), relation + "[" + i + "]");
            }
        }
    }

    @Test
    void emptyExpandIsTheSameAsAbsent() {
        assertEquals(get("PEOPLE", 1, null), get("PEOPLE", 1, List.of()));
    }

    @Test
    void nullExpandIsTheSameAsAbsent() {
        Map<String, Object> args = new HashMap<>();
        args.put("resource", "PEOPLE");
        args.put("id", 1);
        args.put("expand", null);
        ToolResponse r = call("sw_get", args);
        assertFalse(r.isError(), text(r));
        assertEquals(get("PEOPLE", 1, null), text(r));
    }

    @Test
    void duplicateNamesCountOnce() {
        JsonObject luke = new JsonObject(get("PEOPLE", 1, List.of("films", "films", "homeworld")));
        assertEquals(List.of("homeworld", "films"), List.copyOf(luke.getJsonObject("expanded").fieldNames()));
    }

    @Test
    void starMixedWithNamesIsAToolError() {
        assertTrue(error("PEOPLE", 1, List.of("*", "films")).contains("use * alone"));
    }

    @Test
    void unknownRelationIsAToolErrorListingTheValidNames() {
        assertEquals("Unknown relation 'nope' for people. Valid: homeworld, films, species, starships, or *.",
                error("PEOPLE", 1, List.of("nope")));
        assertEquals("Unknown relation 'homeworld' for films. Valid: characters, planets, starships, vehicles, "
                + "species, or *.", error("FILMS", 1, List.of("homeworld")));
    }

    // Checked before any read: with an id that does not exist, the limit is
    // still the error.
    @Test
    void moreThan8ItemsIsAToolError() {
        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add("films");
        }
        assertTrue(error("PEOPLE", 99999, nine).contains("at most 8"), error("PEOPLE", 99999, nine));
        List<String> eight = nine.subList(0, 8);
        assertFalse(get("PEOPLE", 1, eight).isEmpty());
    }

    @Test
    void nameLongerThan40IsAToolError() {
        String message = error("PEOPLE", 99999, List.of("h".repeat(41)));
        assertTrue(message.contains("40 characters"), message);
        assertFalse(message.contains("hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh"), "the long name is not echoed: " + message);
    }

    // Every relation of every record points to a record that exists: with
    // "*" there is no null, except a single relation the data leaves empty.
    // The bean directly (the MCP path is the tests above): 260 tools/call
    // through the test client cost about 45 s of polling.
    @Test
    void everyRelationOfEveryRecordResolves() {
        List<String> nulls = new ArrayList<>();
        int records = 0;
        RequestBaseUrl.set("http://every.example/api");
        try {
            for (String type : TYPES) {
                String lower = type.toLowerCase();
                for (int id : known.ids(lower)) {
                    records++;
                    var entity = expansion.entity(lower, id);
                    JsonObject record = new JsonObject(expansion.expand(lower, entity, jsonb.toJson(entity),
                            expansion.relations(lower, List.of("*"))));
                    collectNulls(lower, id, record, nulls);
                }
            }
        } finally {
            RequestBaseUrl.clear();
        }
        assertEquals(260, records);
        assertTrue(nulls.isEmpty(), "dangling relations: " + nulls);
    }

    private static void collectNulls(String lower, int id, JsonObject record, List<String> nulls) {
        JsonObject expanded = record.getJsonObject("expanded");
        for (RecordFields.Relation relation : RecordFields.relations(lower)) {
            Object value = expanded.getValue(relation.key());
            if (relation.single()) {
                String url = record.getString(relation.key());
                if ((url != null && !url.isEmpty()) != (value != null)) {
                    nulls.add(lower + "/" + id + "." + relation.key());
                }
            } else {
                JsonArray array = (JsonArray) value;
                assertEquals(record.getJsonArray(relation.key()).size(), array.size());
                for (int i = 0; i < array.size(); i++) {
                    if (array.getValue(i) == null) {
                        nulls.add(lower + "/" + id + "." + relation.key() + "[" + i + "]");
                    }
                }
            }
        }
    }

    static int firstId(String type) {
        return switch (type) {
            case "STARSHIPS" -> 2;
            case "VEHICLES" -> 4;
            default -> 1;
        };
    }
}
