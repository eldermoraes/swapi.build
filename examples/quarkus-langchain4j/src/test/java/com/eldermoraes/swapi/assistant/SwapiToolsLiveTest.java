package com.eldermoraes.swapi.assistant;

import com.eldermoraes.swapi.assistant.tools.SwapiTools;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hits the public swapi.build REST API. Run with: ./mvnw test -Dgroups=live
 */
@Tag("live")
@QuarkusTest
class SwapiToolsLiveTest {

    @Inject
    SwapiTools tools;

    @Test
    void findsLukeAndHisHomePlanet() {
        assertTrue(tools.searchPeople("Luke").contains("Luke Skywalker"));
        assertTrue(tools.planet(1).contains("Tatooine"));
    }
}
