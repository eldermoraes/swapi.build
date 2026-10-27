package com.eldermoraes.mcp;

import com.eldermoraes.RequestBaseUrl;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Inject
    SwapiTools tools;

    // 2.13.0 (slice F1): withBaseUrl sets the base for the call and puts back
    // whatever the thread had before, on success and on a tool error. This
    // profile fixes swapi.public-base-url, so a direct call needs no request.
    @Test
    public void directToolCallRestoresThePreviousBaseUrl() {
        RequestBaseUrl.set("previous");
        try {
            String luke = tools.sw_get(SwapiTools.SwResource.PEOPLE, 1, List.of("homeworld"));
            assertTrue(luke.contains("https://config-wins.example/api/planets/1"), luke);
            assertEquals("previous", RequestBaseUrl.get());
            assertTrue(tools.sw_list(SwapiTools.SwResource.FILMS, List.of("title")).contains("A New Hope"));
            assertEquals("previous", RequestBaseUrl.get());
            assertThrows(ToolCallException.class, () -> tools.sw_get(SwapiTools.SwResource.PEOPLE, 99999, null));
            assertEquals("previous", RequestBaseUrl.get(), "the error path restores too");
            assertThrows(ToolCallException.class,
                    () -> tools.sw_get(SwapiTools.SwResource.PEOPLE, 1, List.of("nope")));
            assertEquals("previous", RequestBaseUrl.get());
        } finally {
            RequestBaseUrl.clear();
        }
        tools.sw_random(SwapiTools.SwResource.PLANETS);
        assertNull(RequestBaseUrl.get(), "a thread that had no base is left without one");
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
                                "explicit config should beat discovery, got: " + json);
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
