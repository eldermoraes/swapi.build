package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SwapiStatelessTest {

    @Test
    public void statelessClientCallsToolWithoutInitialize() {
        var client = McpAssured.newStreamableClient()
                .setStateless()
                .build()
                .connect();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        assertTrue(r.content().get(0).asText().text().contains("Luke Skywalker"));
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
