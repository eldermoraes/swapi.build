package com.eldermoraes.seo;

import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which /resource/{type}/{id} pages exist (2.9.1). Plain JUnit: the services
 * load their JSON in the constructor. The cases are real gaps of the data:
 * people has no 17, starships starts at 2 with holes, vehicles starts at 4.
 */
class KnownEntitiesTest {

    private final KnownEntities entities = new KnownEntities(new PeopleService(), new FilmService(),
            new PlanetService(), new SpecieService(), new StarshipService(), new VehicleService());

    @ParameterizedTest(name = "{0}/{1} -> {2}")
    @CsvSource({
            "people, 1, true",
            "people, 83, true",
            "people, 17, false",
            "people, 9999, false",
            "starships, 1, false",
            "starships, 2, true",
            "vehicles, 4, true",
            "films, 6, true",
            "films, 7, false",
            "planets, 1, true",
            "species, 1, true",
            // Only the canonical form of an id: a variant must not be a copy of the page.
            "people, 01, false",
            "people, abc, false",
            "people, 1.5, false",
            "people, 2147483648, false",
            "people, 1234567890, false",
            "people, -1, false",
            "people, 0, false",
            "people, '', false",
            "people, ' 1', false",
            "droids, 1, false",
            "People, 1, false",
    })
    void existsOnlyForACanonicalIdOfARealEntity(String resource, String id, boolean expected) {
        assertEquals(expected, entities.exists(resource, id));
    }

    @Test
    void nullsAreNotEntities() {
        assertEquals(false, entities.exists(null, "1"));
        assertEquals(false, entities.exists("people", null));
    }
}
