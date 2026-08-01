package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SwapiBaseUrlOverrideTest.OverrideProfile.class)
public class SwapiBaseUrlOverrideTest {

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.public-base-url", "https://config-wins.example/api");
        }
    }

    @Test
    public void explicitConfigBeatsDiscovery() {
        var client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        String json = r.content().get(0).asText().text();
                        assertTrue(json.contains("https://config-wins.example/api/people/1"),
                                "config explicita deveria vencer o discovery, veio: " + json);
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
