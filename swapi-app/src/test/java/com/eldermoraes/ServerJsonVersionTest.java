package com.eldermoraes;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the MCP Registry manifest: server.json must exist at the repo root and
 * advertise the pom version, the canonical build.swapi name, and the production
 * Streamable HTTP endpoint. Republishing on release is docs/RELEASE.md's job;
 * this test only stops the manifest from drifting. Plain JUnit — no Quarkus boot;
 * jakarta.json (JSON-P) because the app stack is JSON-B, not Jackson.
 */
class ServerJsonVersionTest {

    private static JsonObject serverJson() {
        Path path = ReleaseMetadata.repoRoot().resolve("server.json");
        assertTrue(Files.isRegularFile(path), () -> "server.json not found at " + path);
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(path))) {
            return reader.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    @Test
    void versionMatchesPom() {
        assertEquals(ReleaseMetadata.pomVersion(), serverJson().getString("version", ""),
                "server.json version drifted from the pom. Bump both together, "
                        + "then republish per docs/RELEASE.md.");
    }

    @Test
    void identityIsCanonical() {
        JsonObject json = serverJson();
        assertEquals("build.swapi/star-wars", json.getString("name", ""),
                "registry name is part of the public identity — never rename casually");
        assertFalse(json.getString("title", "").isBlank(), "title is required");
        String description = json.getString("description", "");
        assertFalse(description.isBlank(), "description is required");
        assertTrue(description.length() <= 100,
                "the registry rejects descriptions over 100 chars at publish time (422)");
    }

    @Test
    void descriptionCarriesTheSearchTerms() {
        String description = serverJson().getString("description", "");
        for (String term : List.of("Star Wars", "MCP", "SWAPI", "No API key")) {
            assertTrue(description.contains(term), () -> "description lacks '" + term + "': " + description);
        }
    }

    // 2.13.0 (slice F1): the description names the six resource types and
    // still fits the 100 characters of the 2025-12-11 schema.
    @Test
    void descriptionFitsTheRegistryLimit() {
        String description = serverJson().getString("description", "");
        assertTrue(description.length() <= 100, description.length() + " characters: " + description);
        for (String type : List.of("people", "films", "planets", "species", "starships", "vehicles")) {
            assertTrue(description.contains(type), () -> "description lacks '" + type + "': " + description);
        }
    }

    /**
     * The registry shows these icons, so each must be a file this repo serves on
     * the production host (webui/public/), in a type the schema allows. The PNG
     * comes first: clients must render PNG, SVG only should.
     */
    @Test
    void iconsAreHttpsFilesThisRepoServes() throws IOException {
        JsonArray icons = serverJson().getJsonArray("icons");
        assertNotNull(icons, "server.json has no icons");
        assertFalse(icons.isEmpty(), "server.json has no icons");
        assertEquals("image/png", icons.getJsonObject(0).getString("mimeType", ""), "the first icon must be a PNG");
        Path publicDir = ReleaseMetadata.repoRoot().resolve("swapi-app/src/main/webui/public");
        Set<String> allowed = Set.of("image/png", "image/jpeg", "image/jpg", "image/svg+xml", "image/webp");
        for (JsonObject icon : icons.getValuesAs(JsonObject.class)) {
            String src = icon.getString("src", "");
            assertTrue(src.startsWith("https://swapi.build/"), src);
            Path file = publicDir.resolve(src.substring("https://swapi.build/".length()));
            assertTrue(Files.isRegularFile(file), () -> src + " is not a file in " + publicDir);
            String mimeType = icon.getString("mimeType", "");
            assertTrue(allowed.contains(mimeType), mimeType + " is not in the schema enum");
            if ("image/png".equals(mimeType)) {
                // IHDR: width and height are the big-endian ints at bytes 16-23.
                ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(file), 16, 8);
                String size = header.getInt() + "x" + header.getInt();
                assertEquals(List.of(size), icon.getJsonArray("sizes").getValuesAs(JsonString.class).stream()
                        .map(JsonString::getString).toList(), src + " sizes must match the PNG header");
            }
        }
    }

    @Test
    void remoteIsTheProductionStreamableHttpEndpoint() {
        JsonArray remotes = serverJson().getJsonArray("remotes");
        assertEquals(1, remotes.size(), "exactly one remote: the production endpoint");
        JsonObject remote = remotes.getJsonObject(0);
        assertEquals("streamable-http", remote.getString("type", ""));
        assertEquals("https://swapi.build/mcp", remote.getString("url", ""));
    }
}
