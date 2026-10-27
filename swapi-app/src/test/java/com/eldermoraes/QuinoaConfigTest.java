package com.eldermoraes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guard: Quinoa's SPA routing stays off (2.9.1). SeoRoutes serves every
 * document of the SPA itself; the Quinoa fallback only rerouted what SeoRoutes
 * let through to "/", which is where the soft 404s came from: 200 with the home
 * page for unknown paths, index,follow on dotted paths, the HTML in place of a
 * missing asset, and every rerouted request counted twice in /stats.
 *
 * The suite runs with Quinoa disabled, so no @QuarkusTest can see the reroute;
 * this reads the key itself. Plain JUnit, no Quarkus boot.
 */
class QuinoaConfigTest {

    @Test
    void spaRoutingIsOffBecauseSeoRoutesRoutesDocuments() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals("false", properties.getProperty("quarkus.quinoa.enable-spa-routing"),
                "Quinoa's SPA routing must stay off: SeoRoutes decides 200 or 404 for every page");
    }
}
