package com.eldermoraes.swapi.assistant;

import com.eldermoraes.swapi.assistant.ai.Archivist;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the MCP path is wired without calling a model.
 *
 * <p>Booting Quarkus builds the CDI container and the AI-service proxy, which is
 * what validates {@code @McpToolBox("swapi")} against the configured client. It
 * does not open a connection: the MCP client dials the server when its bean is
 * first created, and in the test profile the health check that would force that
 * at boot is disabled. Injecting {@code McpClient} below is therefore what
 * triggers the handshake -- against {@link McpStubServer}, so the run stays
 * offline.
 *
 * <p>{@code restrictToAnnotatedClass = true} is load-bearing: the stub overrides
 * {@code quarkus.langchain4j.mcp.swapi.url}, and by default that override would
 * apply to the whole test run, including the live {@code McpConnectivityTest}
 * gate that must keep dialing production.
 */
@QuarkusTest
@QuarkusTestResource(value = McpStubServer.class, restrictToAnnotatedClass = true)
class ArchivistWiringTest {

    @Inject
    Archivist archivist;

    /**
     * The MCP client connects when this bean is first created, not when Quarkus
     * starts, so injecting it here is what makes the handshake happen offline.
     */
    @Inject
    @McpClientName("swapi")
    McpClient swapi;

    @ConfigProperty(name = "quarkus.langchain4j.mcp.swapi.url")
    String mcpUrl;

    @Test
    void mcpArchivistIsWired() {
        assertNotNull(archivist);
    }

    @Test
    void theStubServedTheHandshakeAndTheToolList() {
        assertTrue(mcpUrl.contains("localhost"),
                "McpStubServer did not override the MCP URL, so this test would reach the "
                        + "public server; effective quarkus.langchain4j.mcp.swapi.url is: " + mcpUrl);

        // The stub advertises exactly one tool; production advertises four. An
        // equality check here therefore fails loudly if the override ever slips
        // and the offline suite starts talking to swapi.build.
        List<String> names = swapi.listTools().stream()
                .map(tool -> tool.name())
                .toList();
        assertEquals(List.of("sw_get"), names, "expected only the stub's tool, got: " + names);

        List<String> served = McpStubServer.servedMethods();
        assertTrue(served.contains("initialize"),
                "the MCP client did not initialize against the stub; served: " + served);
        assertTrue(served.contains("tools/list"),
                "the MCP client did not list tools from the stub; served: " + served);
    }
}
