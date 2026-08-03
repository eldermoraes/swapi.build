package com.eldermoraes.swapi.assistant;

import com.eldermoraes.swapi.assistant.tools.SwapiTools;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hits the public swapi.build REST API. Run with: ./mvnw test -Dgroups=live
 */
@Tag("live")
@QuarkusTest
class SwapiToolsLiveTest {

    /** Trailing id of a {@code "homeworld":"https://swapi.build/api/planets/1"} field. */
    private static final Pattern HOMEWORLD =
            Pattern.compile("\"homeworld\"\\s*:\\s*\"[^\"]*/(\\d+)/?\"");

    @Inject
    SwapiTools tools;

    /**
     * Also checks the premise the {@code planet} tool description teaches the model:
     * that a person's payload carries a {@code homeworld} URL whose trailing number is
     * the planet id. Hardcoding the id here would leave that unverified.
     */
    @Test
    void findsLukeAndHisHomePlanet() {
        String luke = tools.searchPeople("Luke");
        assertTrue(luke.contains("Luke Skywalker"), "expected Luke Skywalker, got: " + luke);

        Matcher homeworld = HOMEWORLD.matcher(luke);
        assertTrue(homeworld.find(), "no homeworld URL to derive a planet id from: " + luke);
        int planetId = Integer.parseInt(homeworld.group(1));

        assertTrue(tools.planet(planetId).contains("Tatooine"),
                "planet " + planetId + ", taken from Luke's homeworld URL, is not Tatooine");
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
