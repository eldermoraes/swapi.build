package com.eldermoraes.seo.ssr;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.seo.KnownEntities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the template of detail shows of each type (2.12.0, slice E2): the scalar
 * fields in the order of the model and the relations the API returns, by
 * explicit getters. Plain JUnit: the services load their JSON in the constructor.
 */
class RecordFieldsTest {

    @AfterEach
    void clear() {
        RequestBaseUrl.clear();
    }

    @Test
    void theAttributesOfEachType() {
        assertEquals(List.of("height", "mass", "hair_color", "skin_color", "eye_color", "birth_year", "gender"),
                keys("people"));
        assertEquals(List.of("episode_id", "director", "producer", "release_date"), keys("films"));
        assertEquals(List.of("rotation_period", "orbital_period", "diameter", "climate", "gravity", "terrain",
                "surface_water", "population"), keys("planets"));
        assertEquals(List.of("classification", "designation", "average_height", "skin_colors", "hair_colors",
                "eye_colors", "average_lifespan", "language"), keys("species"));
        assertEquals(List.of("model", "manufacturer", "cost_in_credits", "length", "max_atmosphering_speed", "crew",
                "passengers", "cargo_capacity", "consumables", "hyperdrive_rating", "MGLT", "starship_class"),
                keys("starships"));
        assertEquals(List.of("model", "manufacturer", "cost_in_credits", "length", "max_atmosphering_speed", "crew",
                "passengers", "cargo_capacity", "consumables", "vehicle_class"), keys("vehicles"));
    }

    @Test
    void theRelationsOfEachTypeAndWhereTheyPoint() {
        assertEquals(List.of("homeworld:planets", "films:films", "species:species", "starships:starships"),
                relations("people"));
        assertEquals(List.of("characters:people", "planets:planets", "starships:starships", "vehicles:vehicles",
                "species:species"), relations("films"));
        assertEquals(List.of("residents:people", "films:films"), relations("planets"));
        assertEquals(List.of("homeworld:planets", "people:people", "films:films"), relations("species"));
        assertEquals(List.of("pilots:people", "films:films"), relations("starships"));
        assertEquals(List.of("pilots:people", "films:films"), relations("vehicles"));
    }

    @Test
    void neverTheUrlNorTheTimestampsInTheTable() {
        for (String type : KnownEntities.TYPES) {
            for (String key : keys(type)) {
                assertFalse(List.of("url", "created", "edited", "name", "title").contains(key), type + "." + key);
            }
        }
        assertFalse(keys("films").contains("opening_crawl"), "the crawl has its own paragraph");
    }

    @Test
    void theValuesComeFromTheGettersAsTheyAreInTheData() {
        RequestBaseUrl.set("https://a.example/api");
        var luke = new PeopleService().getPeopleById(1);
        assertEquals(List.of("172", "77", "blond", "fair", "blue", "19BBY", "male"),
                RecordFields.attributes("people").stream().map(f -> f.value().apply(luke)).toList());
        assertEquals("Height", RecordFields.attributes("people").getFirst().label());
        var film = new FilmService().getFilmById(1);
        assertEquals("4", RecordFields.attributes("films").getFirst().value().apply(film));
        assertEquals("Episode", RecordFields.attributes("films").getFirst().label());
        var droid = new SpecieService().getSpecieById(2);
        assertEquals(List.of(), RecordFields.relations("species").getFirst().urls().apply(droid),
                "Droid has no homeworld: the block is left out");
        var relations = RecordFields.relations("people");
        assertEquals(List.of("https://a.example/api/planets/1"), relations.getFirst().urls().apply(luke));
        var starship = new StarshipService().getStarshipById(10);
        assertEquals("MGLT", RecordFields.attributes("starships").get(10).label());
        assertEquals("75", RecordFields.attributes("starships").get(10).value().apply(starship));
    }

    // 2.13.0 (slice F1): single decides whether the MCP expand of a relation is
    // one record (or null) or an array; it is declared, never read from the data.
    @Test
    void onlyHomeworldIsSingle() {
        for (String type : KnownEntities.TYPES) {
            for (RecordFields.Relation relation : RecordFields.relations(type)) {
                assertEquals("homeworld".equals(relation.key()), relation.single(), type + "." + relation.key());
            }
        }
        assertEquals(2, KnownEntities.TYPES.stream()
                .flatMap(type -> RecordFields.relations(type).stream())
                .filter(RecordFields.Relation::single).count(), "people.homeworld and species.homeworld");
    }

    @Test
    void anUnknownTypeIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> RecordFields.attributes("droids"));
        assertThrows(IllegalArgumentException.class, () -> RecordFields.relations("droids"));
    }

    private static List<String> keys(String type) {
        return RecordFields.attributes(type).stream().map(RecordFields.Field::key).toList();
    }

    private static List<String> relations(String type) {
        return RecordFields.relations(type).stream().map(r -> r.key() + ":" + r.type()).toList();
    }
}
