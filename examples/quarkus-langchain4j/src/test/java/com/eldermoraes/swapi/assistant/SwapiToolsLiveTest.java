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

    /**
     * The model guesses ids, and the API answers a bad guess with 404. That must come
     * back as something the model can read and recover from, not as an exception that
     * aborts the whole AI call.
     */
    @Test
    void unknownIdsAreReportedToTheModelInsteadOfThrowing() {
        String person = tools.person(9999);
        assertTrue(person.contains("no person with id 9999"),
                "expected a readable not-found payload, got: " + person);

        String planet = tools.planet(9999);
        assertTrue(planet.contains("no planet with id 9999"),
                "expected a readable not-found payload, got: " + planet);
    }
}
