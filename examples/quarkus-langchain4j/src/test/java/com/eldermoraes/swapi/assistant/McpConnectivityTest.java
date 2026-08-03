package com.eldermoraes.swapi.assistant;

import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hits the public swapi.build MCP server. Excluded from the default suite;
 * run it with: ./mvnw test -Dgroups=live
 */
@Tag("live")
@QuarkusTest
class McpConnectivityTest {

    @Inject
    @McpClientName("swapi")
    McpClient swapi;

    /**
     * The URL the client will actually dial. A local stub (see McpStubServer)
     * exposes the same four tool names, so without this the gate could pass
     * while touching no network at all.
     */
    @ConfigProperty(name = "quarkus.langchain4j.mcp.swapi.url")
    String mcpUrl;

    @Test
    void listsTheSwapiTools() {
        assertTrue(mcpUrl.startsWith("https://"),
                "the live gate was retargeted: it must dial the public swapi.build MCP "
                        + "server over https, but the effective "
                        + "quarkus.langchain4j.mcp.swapi.url is: " + mcpUrl);
        assertFalse(mcpUrl.contains("localhost") || mcpUrl.contains("127.0.0.1"),
                "the live gate was retargeted at a local stub and is no longer verifying "
                        + "production; effective quarkus.langchain4j.mcp.swapi.url is: " + mcpUrl);

        List<String> names = swapi.listTools().stream()
                .map(tool -> tool.name())
                .toList();

        assertTrue(names.containsAll(List.of("sw_list", "sw_get", "sw_random", "sw_search")),
                "expected the four swapi.build tools, got: " + names);
    }
}
