package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SwapiBaseUrlDiscoveryTest {

    @Test
    public void embeddedUrlsFollowRequestHost() {
        var client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        String json = r.content().get(0).asText().text();
                        assertTrue(json.contains("http://localhost:8081/api/people/1"),
                                "expected URL derived from the request host, got: " + json);
                        assertFalse(json.contains("swapi.build/api"),
                                "there should be no hardcoded production domain: " + json);
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
