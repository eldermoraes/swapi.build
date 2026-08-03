package com.eldermoraes.swapi.assistant;

import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void listsTheSwapiTools() {
        List<String> names = swapi.listTools().stream()
                .map(tool -> tool.name())
                .toList();

        assertTrue(names.containsAll(List.of("sw_list", "sw_get", "sw_random", "sw_search")),
                "expected the four swapi.build tools, got: " + names);
    }
}
