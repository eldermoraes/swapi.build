package com.eldermoraes.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guard: the MCP server info carries no fixed domain. A website URL or icons in
 * application.properties would put a hardcoded domain in the binary, against
 * the rule that the public base URL is discovered per request (CLAUDE.md).
 * Plain JUnit, no Quarkus boot; reads the keys, so a comment may name them.
 */
class McpConfigTest {

    @Test
    void noHardcodedWebsiteUrl() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            assertFalse(key.contains("server-info.website-url"), key + " would hardcode the domain");
            assertFalse(key.contains("server-info.icons"), key + " would hardcode the domain");
        }
    }
}
