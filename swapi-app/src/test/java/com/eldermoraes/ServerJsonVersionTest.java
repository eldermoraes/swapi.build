package com.eldermoraes;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void remoteIsTheProductionStreamableHttpEndpoint() {
        JsonArray remotes = serverJson().getJsonArray("remotes");
        assertEquals(1, remotes.size(), "exactly one remote: the production endpoint");
        JsonObject remote = remotes.getJsonObject(0);
        assertEquals("streamable-http", remote.getString("type", ""));
        assertEquals("https://swapi.build/mcp", remote.getString("url", ""));
    }
}
