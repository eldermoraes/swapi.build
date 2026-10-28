package com.eldermoraes;

import com.eldermoraes.seo.ssr.RecordFields;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.json.Json;
import jakarta.json.JsonReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (fix-people-vehicles): people lost their vehicles field. The data
 * file had it, the model did not, so REST, OpenAPI, MCP and the record pages all
 * served people without it while swapi.dev serves it. Every type must carry the
 * fields swapi.dev serves, on every surface. The swapi.dev sets are a local
 * fixture (swapi-dev-fields.json), read on 2026-10-28; no network here.
 */
@QuarkusTest
class SwapiDevFieldsTest {

    private static final Map<String, String> SCHEMA = Map.of("people", "People", "films", "Film", "planets", "Planet",
            "species", "Specie", "starships", "Starship", "vehicles", "Vehicle");

    private static McpStreamableTestClient client;

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
    void restRecordHasTheFieldsOfSwapiDev(String type) {
        Map<String, Object> record = given().when().get("/api/" + type + "/" + fixtureId(type))
                .then().statusCode(200).extract().jsonPath().getMap("");
        assertEquals(fixture(type), new TreeSet<>(record.keySet()), type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
    void everyRecordOfTheListHasTheFieldsOfSwapiDev(String type) {
        List<Map<String, Object>> all = given().when().get("/api/" + type)
                .then().statusCode(200).extract().jsonPath().getList("");
        assertFalse(all.isEmpty(), type);
        for (Map<String, Object> record : all) {
            assertEquals(fixture(type), new TreeSet<>(record.keySet()), type + " " + record.get("url"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
    void openApiSchemaHasTheFieldsOfSwapiDev(String type) {
        Map<String, Object> properties = given().accept("*/*").when().get("/openapi.json")
                .then().statusCode(200).extract().jsonPath()
                .getMap("components.schemas." + SCHEMA.get(type) + ".properties");
        assertEquals(fixture(type), new TreeSet<>(properties.keySet()), type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
    void mcpRecordHasTheFieldsOfSwapiDev(String type) {
        JsonObject record = new JsonObject(text(call("sw_get",
                Map.of("resource", type.toUpperCase(), "id", fixtureId(type)))));
        assertEquals(fixture(type), new TreeSet<>(record.fieldNames()), type);
    }

    @Test
    void lukeHasHisTwoVehiclesEverywhere() {
        List<String> rest = given().when().get("/api/people/1").then().statusCode(200).extract().jsonPath()
                .getList("vehicles");
        assertEquals(2, rest.size(), rest.toString());
        assertTrue(rest.get(0).endsWith("/api/vehicles/14"), rest.toString());
        assertTrue(rest.get(1).endsWith("/api/vehicles/30"), rest.toString());

        JsonObject expanded = new JsonObject(text(call("sw_get",
                Map.of("resource", "PEOPLE", "id", 1, "expand", List.of("vehicles"))))).getJsonObject("expanded");
        assertEquals(List.of("Snowspeeder", "Imperial Speeder Bike"),
                expanded.getJsonArray("vehicles").stream().map(v -> ((JsonObject) v).getString("name")).toList());

        assertTrue(RecordFields.relations("people").stream().anyMatch(r -> r.key().equals("vehicles")));
        String page = given().accept("text/html").when().get("/resource/people/1").then().statusCode(200)
                .extract().asString();
        assertTrue(page.contains("/resource/vehicles/14"), "the record page links the Snowspeeder");
    }

    private static ToolResponse call(String tool, Map<String, Object> arguments) {
        if (client == null) {
            client = McpAssured.newConnectedStreamableClient();
        }
        AtomicReference<ToolResponse> response = new AtomicReference<>();
        client.when().toolsCall(tool).withArguments(arguments).withAssert(response::set).send().thenAssertResults();
        assertNotNull(response.get());
        assertFalse(response.get().isError(), text(response.get()));
        return response.get();
    }

    private static String text(ToolResponse r) {
        return r.content().get(0).asText().text();
    }

    private static jakarta.json.JsonObject fixtureFile() {
        try (InputStream in = SwapiDevFieldsTest.class.getResourceAsStream("/swapi-dev-fields.json");
             JsonReader reader = Json.createReader(in)) {
            return reader.readObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int fixtureId(String type) {
        return fixtureFile().getJsonObject(type).getInt("id");
    }

    private static Set<String> fixture(String type) {
        Set<String> fields = new TreeSet<>();
        fixtureFile().getJsonObject(type).getJsonArray("fields")
                .forEach(v -> fields.add(((jakarta.json.JsonString) v).getString()));
        return fields;
    }
}
