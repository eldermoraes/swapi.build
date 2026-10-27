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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    // The fields the metadata templates of seo/site.json may use (2.10.0).
    @Test
    void fieldsOfAFilm() {
        assertEquals(Optional.of(Map.of("title", "A New Hope", "episode_id", "4",
                        "director", "George Lucas", "release_date", "1977-05-25")),
                entities.fields("films", "1"));
    }

    @Test
    void fieldsOfAPersonAndOfEachOtherType() {
        assertEquals(Optional.of(Map.of("name", "Luke Skywalker")), entities.fields("people", "1"));
        assertEquals(Optional.of(Map.of("name", "Tatooine")), entities.fields("planets", "1"));
        assertEquals(Optional.of(Map.of("name", "Human")), entities.fields("species", "1"));
        assertEquals(Optional.of(Map.of("name", "CR90 corvette")), entities.fields("starships", "2"));
        assertEquals(Optional.of(Map.of("name", "Sand Crawler")), entities.fields("vehicles", "4"));
    }

    @Test
    void noFieldsForARecordThatDoesNotExist() {
        assertEquals(Optional.empty(), entities.fields("people", "17"));
        assertEquals(Optional.empty(), entities.fields("people", "01"));
        assertEquals(Optional.empty(), entities.fields("droids", "1"));
    }

    @Test
    void idsInAscendingOrder() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6), entities.ids("films"));
        List<Integer> people = entities.ids("people");
        assertEquals(82, people.size());
        assertFalse(people.contains(17));
        assertEquals(people.stream().sorted().toList(), people);
        assertEquals(List.of(), entities.ids("droids"));
    }

    // The text of a link to a related record in the template of detail (2.12.0):
    // the title of a film, the name of anything else; never read from getUrl().
    @Test
    void theNameOfARecord() {
        assertEquals(Optional.of("Tatooine"), entities.name("planets", "1"));
        assertEquals(Optional.of("A New Hope"), entities.name("films", "1"));
        assertEquals(Optional.of("Luke Skywalker"), entities.name("people", "1"));
        assertEquals(Optional.of("Droid"), entities.name("species", "2"));
        assertEquals(Optional.of("CR90 corvette"), entities.name("starships", "2"));
        assertEquals(Optional.of("Sand Crawler"), entities.name("vehicles", "4"));
        assertEquals(Optional.empty(), entities.name("people", "17"));
        assertEquals(Optional.empty(), entities.name("people", "01"));
        assertEquals(Optional.empty(), entities.name("droids", "1"));
    }

    @Test
    void theSupportedFieldsOfEachType() {
        assertEquals(Set.of("title", "episode_id", "director", "release_date"), KnownEntities.supportedFields("films"));
        assertEquals(Set.of("name"), KnownEntities.supportedFields("people"));
        assertEquals(Set.of("people", "films", "planets", "species", "starships", "vehicles"), KnownEntities.TYPES);
    }
}
