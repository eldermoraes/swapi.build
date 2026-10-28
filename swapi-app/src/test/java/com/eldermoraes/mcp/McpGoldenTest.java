package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compatibility of 2.13.0 (slice F1): without the new arguments (expand,
 * fields), the text of every tool call is byte for byte the text 2.12.1 gave,
 * and the required arguments and the type of every old property do not change.
 *
 * The goldens were recorded on the 2.12.1 code, in the test profile (host
 * http://localhost:8081/api), with {@code GOLDEN_UPDATE=1 ./mvnw test
 * -Dtest=McpGoldenTest}. They are the contract of 2.12.1: never re-record them
 * on a later version to make this test pass. sw_random is left out (random).
 */
@QuarkusTest
class McpGoldenTest {

    private static final Path DIR = Path.of("src/test/resources/mcp/golden-2.12.1");
    private static final boolean UPDATE = "1".equals(System.getenv("GOLDEN_UPDATE"));

    /**
     * The lowest record id of each type, in the data of 2.12.1: 1, except
     * STARSHIPS (2) and VEHICLES (4), which have no record 1 (that call is the
     * error golden error-sw_get-starships-1).
     */
    private static final Map<String, Integer> FIRST_ID = Map.of(
            "PEOPLE", 1, "FILMS", 1, "PLANETS", 1, "SPECIES", 1, "STARSHIPS", 2, "VEHICLES", 4);

    /** The highest record id of each type, in the data of 2.12.1. */
    private static final Map<String, Integer> LAST_ID = Map.of(
            "PEOPLE", 83, "FILMS", 6, "PLANETS", 60, "SPECIES", 37, "STARSHIPS", 75, "VEHICLES", 76);

    private static final List<String> TYPES = List.of("PEOPLE", "FILMS", "PLANETS", "SPECIES", "STARSHIPS", "VEHICLES");

    static McpStreamableTestClient client;

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
        }
    }

    private record Call(String file, String tool, Map<String, Object> arguments, boolean error) {
    }

    private static List<Call> calls() {
        List<Call> calls = new ArrayList<>();
        for (String type : TYPES) {
            String lower = type.toLowerCase();
            int first = FIRST_ID.get(type);
            calls.add(new Call("sw_get-" + lower + "-" + first, "sw_get", Map.of("resource", type, "id", first), false));
            int last = LAST_ID.get(type);
            calls.add(new Call("sw_get-" + lower + "-" + last, "sw_get", Map.of("resource", type, "id", last), false));
            calls.add(new Call("sw_list-" + lower, "sw_list", Map.of("resource", type), false));
        }
        calls.add(new Call("sw_search-people-sky", "sw_search", Map.of("resource", "PEOPLE", "query", "sky"), false));
        calls.add(new Call("sw_search-films-hope", "sw_search", Map.of("resource", "FILMS", "query", "hope"), false));
        calls.add(new Call("sw_search-people-zzz", "sw_search", Map.of("resource", "PEOPLE", "query", "zzz"), false));
        calls.add(new Call("sw_search-people-empty", "sw_search", Map.of("resource", "PEOPLE", "query", ""), false));
        calls.add(new Call("error-sw_get-films-99", "sw_get", Map.of("resource", "FILMS", "id", 99), true));
        calls.add(new Call("error-sw_get-starships-1", "sw_get", Map.of("resource", "STARSHIPS", "id", 1), true));
        calls.add(new Call("error-sw_list-droids", "sw_list", Map.of("resource", "DROIDS"), true));
        return calls;
    }

    @Test
    void toolResultsWithoutNewArgumentsMatchTheGoldens() throws IOException {
        List<String> differ = new ArrayList<>();
        for (Call call : calls()) {
            AtomicReference<ToolResponse> response = new AtomicReference<>();
            client().when()
                    .toolsCall(call.tool())
                    .withArguments(call.arguments())
                    .withAssert(response::set)
                    .send()
                    .thenAssertResults();
            ToolResponse r = response.get();
            assertNotNull(r, call.file());
            assertEquals(call.error(), r.isError(), call.file());
            assertEquals(1, r.content().size(), call.file());
            String text = r.content().get(0).asText().text();
            if (!UPDATE && call.file().contains("people") && !r.isError()) {
                text = withoutPeopleVehicles(call.file(), text);
            }
            if (!compare(call.file() + ".txt", text)) {
                differ.add(call.file());
            }
        }
        assertTrue(differ.isEmpty(), "differ from the 2.12.1 goldens: " + differ);
    }

    // The schema of each tool, reduced to what a client of 2.12.1 relies on:
    // the required arrays and the type of each property 2.12.1 had. New
    // optional properties may appear; none of these may change.
    @Test
    void requiredArgumentsAndTypesAreUnchanged() throws IOException {
        AtomicReference<JsonObject> current = new AtomicReference<>();
        client().when()
                .toolsList(page -> {
                    JsonObject all = new JsonObject();
                    for (String name : List.of("sw_get", "sw_list", "sw_random", "sw_search")) {
                        JsonObject schema = page.findByName(name).inputSchema();
                        JsonObject types = new JsonObject();
                        JsonObject properties = schema.getJsonObject("properties");
                        for (String property : properties.fieldNames().stream().sorted().toList()) {
                            types.put(property, properties.getJsonObject(property).getValue("type"));
                        }
                        all.put(name, new JsonObject()
                                .put("required", schema.getJsonArray("required", new JsonArray()))
                                .put("types", types));
                    }
                    current.set(all);
                })
                .thenAssertResults();
        Path file = DIR.resolve("tools-schema.json");
        if (UPDATE) {
            write(file, current.get().encodePrettily() + "\n");
        }
        assertTrue(Files.exists(file), "missing golden " + file + " (record it on 2.12.1 with GOLDEN_UPDATE=1)");
        JsonObject golden = new JsonObject(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, String> wrong = new LinkedHashMap<>();
        for (String name : golden.fieldNames()) {
            JsonObject was = golden.getJsonObject(name);
            JsonObject now = current.get().getJsonObject(name);
            if (now == null) {
                fail(name + " is gone from tools/list");
            }
            if (!was.getJsonArray("required").equals(now.getJsonArray("required"))) {
                wrong.put(name + ".required", was.getJsonArray("required") + " -> " + now.getJsonArray("required"));
            }
            for (String property : was.getJsonObject("types").fieldNames()) {
                Object before = was.getJsonObject("types").getValue(property);
                Object after = now.getJsonObject("types").getValue(property);
                if (!java.util.Objects.equals(before, after)) {
                    wrong.put(name + "." + property, before + " -> " + after);
                }
            }
        }
        assertTrue(wrong.isEmpty(), "changed since 2.12.1: " + wrong);
    }

    // The one addition after 2.12.1 (fix-people-vehicles): every people record
    // gained "vehicles", the last key in JSON-B's alphabetical order, as on
    // swapi.dev. The goldens stay the 2.12.1 text: each people record must carry
    // the key, and without it the text must be byte for byte the golden.
    private static final java.util.regex.Pattern PEOPLE_VEHICLES =
            java.util.regex.Pattern.compile(",\"vehicles\":\\[[^\\]]*\\]}");

    private static String withoutPeopleVehicles(String file, String text) {
        long records = java.util.regex.Pattern.compile("\"url\":\"[^\"]*/api/people/\\d+\"").matcher(text).results()
                .count();
        long vehicles = PEOPLE_VEHICLES.matcher(text).results().count();
        assertEquals(records, vehicles, file + ": every people record carries vehicles");
        return PEOPLE_VEHICLES.matcher(text).replaceAll("}");
    }

    private static boolean compare(String name, String actual) throws IOException {
        Path file = DIR.resolve(name);
        if (UPDATE) {
            write(file, actual);
        }
        assertTrue(Files.exists(file), "missing golden " + file + " (record it on 2.12.1 with GOLDEN_UPDATE=1)");
        return Files.readString(file, StandardCharsets.UTF_8).equals(actual);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
