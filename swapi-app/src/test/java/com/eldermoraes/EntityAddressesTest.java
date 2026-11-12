package com.eldermoraes;

import com.eldermoraes.film.Film;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.People;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.Planet;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.specie.Specie;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.Starship;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.Vehicle;
import com.eldermoraes.vehicle.VehicleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The addresses a record gives with and without a request base (issue #62).
 * The data files hold relative addresses ("/people/1"); the getters put the
 * request base (RequestBaseUrl) in front. Every response is produced with a
 * base; without one (the event loop, startup) only the services and
 * KnownEntities read a record, to find it by the end of its address.
 */
class EntityAddressesTest {

    private static final String BASE = "https://a.example/api";

    @BeforeEach
    @AfterEach
    void noBase() {
        RequestBaseUrl.clear();
    }

    @Test
    void withABaseEveryAddressIsAbsolute() {
        RequestBaseUrl.set(BASE);
        People luke = new PeopleService().getPeopleById(1);
        assertEquals(BASE + "/people/1", luke.getUrl());
        assertEquals(BASE + "/planets/1", luke.getHomeworld());
        assertEquals(List.of(BASE + "/films/1", BASE + "/films/2", BASE + "/films/3", BASE + "/films/6"),
                luke.getFilms());
        assertEquals(List.of(), luke.getSpecies());
        assertEquals(List.of(BASE + "/vehicles/14", BASE + "/vehicles/30"), luke.getVehicles());
        assertEquals(List.of(BASE + "/starships/12", BASE + "/starships/22"), luke.getStarships());

        Specie human = new SpecieService().getSpecieById(1);
        assertEquals(BASE + "/species/1", human.getUrl());
        assertEquals(BASE + "/planets/9", human.getHomeworld());
        assertEquals(BASE + "/people/66", human.getPeople().getFirst());

        Film hope = new FilmService().getFilmById(1);
        assertEquals(BASE + "/films/1", hope.getUrl());
        assertEquals(BASE + "/people/1", hope.getCharacters().getFirst());
        assertEquals(BASE + "/planets/1", hope.getPlanets().getFirst());
        assertEquals(BASE + "/starships/2", hope.getStarships().getFirst());
        assertEquals(BASE + "/vehicles/4", hope.getVehicles().getFirst());
        assertEquals(BASE + "/species/1", hope.getSpecies().getFirst());

        Planet tatooine = new PlanetService().getPlanetById(1);
        assertEquals(BASE + "/planets/1", tatooine.getUrl());
        assertEquals(BASE + "/people/1", tatooine.getResidents().getFirst());
        assertEquals(BASE + "/films/1", tatooine.getFilms().getFirst());

        Starship xwing = new StarshipService().getStarshipById(12);
        assertEquals(BASE + "/starships/12", xwing.getUrl());
        assertEquals(BASE + "/people/1", xwing.getPilots().getFirst());
        assertEquals(BASE + "/films/1", xwing.getFilms().getFirst());

        Vehicle snowspeeder = new VehicleService().getVehicleById(14);
        assertEquals(BASE + "/vehicles/14", snowspeeder.getUrl());
        assertEquals(BASE + "/people/1", snowspeeder.getPilots().getFirst());
        assertEquals(BASE + "/films/2", snowspeeder.getFilms().getFirst());
    }

    @Test
    void withoutABaseTheListsAreAsInTheData() {
        People luke = new PeopleService().getPeopleById(1);
        assertEquals(List.of("/films/1", "/films/2", "/films/3", "/films/6"), luke.getFilms());
        assertEquals(List.of("/vehicles/14", "/vehicles/30"), luke.getVehicles());
        assertEquals("/people/1", new FilmService().getFilmById(1).getCharacters().getFirst());
        assertEquals("/people/1", new PlanetService().getPlanetById(1).getResidents().getFirst());
        assertEquals("/people/66", new SpecieService().getSpecieById(1).getPeople().getFirst());
        assertEquals("/people/1", new StarshipService().getStarshipById(12).getPilots().getFirst());
        assertEquals("/people/1", new VehicleService().getVehicleById(14).getPilots().getFirst());
    }

    // Droid (species 2) has no homeworld in the data: swapi.dev's null is "" here.
    @Test
    void anAbsentHomeworldIsEmptyWithOrWithoutABase() {
        assertEquals("", new SpecieService().getSpecieById(2).getHomeworld());
        RequestBaseUrl.set(BASE);
        assertEquals("", new SpecieService().getSpecieById(2).getHomeworld());
        People none = new People();
        assertEquals("", none.getHomeworld());
        none.setHomeworld("null");
        assertEquals("", none.getHomeworld());
        none.setHomeworld("");
        assertEquals("", none.getHomeworld());
    }

    @Test
    void aRecordIsFoundByIdWithOrWithoutABase() {
        for (String base : new String[] {null, BASE}) {
            if (base != null) {
                RequestBaseUrl.set(base);
            }
            assertEquals("Luke Skywalker", new PeopleService().getPeopleById(1).getName());
            assertEquals("A New Hope", new FilmService().getFilmById(1).getTitle());
            assertEquals("Tatooine", new PlanetService().getPlanetById(1).getName());
            assertEquals("Droid", new SpecieService().getSpecieById(2).getName());
            assertEquals("X-wing", new StarshipService().getStarshipById(12).getName());
            assertEquals("Snowspeeder", new VehicleService().getVehicleById(14).getName());
            // people 17 was never in the data; 1 is not the end of 11.
            assertNull(new PeopleService().getPeopleById(17));
            assertNotNull(new PeopleService().getPeopleById(11));
            assertNull(new FilmService().getFilmById(7));
            assertNull(new PeopleService().getPeopleById(0));
            assertNull(new PeopleService().getPeopleById(-1));
        }
    }

    @Test
    void aSearchMatchesAnyCaseAnywhereInTheNameOrTitle() {
        assertEquals(List.of("Luke Skywalker", "Anakin Skywalker", "Shmi Skywalker"),
                new PeopleService().getPeopleByName("SKYWALKER").stream().map(People::getName).toList());
        assertEquals(List.of("A New Hope"),
                new FilmService().getFilmByTitle("hope").stream().map(Film::getTitle).toList());
        assertEquals(List.of("Tatooine"),
                new PlanetService().getPlanetByName("tatoo").stream().map(Planet::getName).toList());
        assertEquals(List.of("Droid"),
                new SpecieService().getSpecieByName("droid").stream().map(Specie::getName).toList());
        assertEquals(List.of("X-wing"),
                new StarshipService().getStarshipByName("x-wing").stream().map(Starship::getName).toList());
        assertEquals(List.of("Snowspeeder"),
                new VehicleService().getVehicleByName("snowsp").stream().map(Vehicle::getName).toList());
        assertEquals(List.of(), new PeopleService().getPeopleByName("no such name"));
    }

    @Test
    void aTypeListsAllItsRecordsAndDrawsOneOfThem() {
        assertEquals(82, new PeopleService().getAllPeople().size());
        assertEquals(6, new FilmService().getAllFilms().size());
        assertEquals(60, new PlanetService().getAllPlanets().size());
        assertEquals(37, new SpecieService().getAllSpecies().size());
        assertEquals(36, new StarshipService().getAllStarships().size());
        assertEquals(39, new VehicleService().getAllVehicles().size());
        PeopleService people = new PeopleService();
        assertNotNull(people.getRandomPeople().getName());
        assertTrue(people.getAllPeople().contains(people.getRandomPeople()));
    }
}
