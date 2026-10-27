package com.eldermoraes.seo;

import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

/**
 * Whether a /resource/{type}/{id} page exists (2.9.1): the id is in its
 * canonical form and the entity is in the data. Anything else is a 404, so a
 * variant such as /resource/people/01 is never a copy of the page of id 1.
 *
 * The services match by {@code getUrl().endsWith("/<type>/<id>")}. getUrl()
 * reads the request base URL from a ThreadLocal (RequestBaseUrl), which on the
 * event loop may be empty ("null/people/1") or hold another request's base;
 * only the suffix is compared and nothing is serialized here, so no URL leaks
 * between requests.
 */
@ApplicationScoped
public class KnownEntities {

    /** At most 9 digits, no leading zero: never overflows an int. */
    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,8}");

    private final PeopleService people;
    private final FilmService films;
    private final PlanetService planets;
    private final SpecieService species;
    private final StarshipService starships;
    private final VehicleService vehicles;

    @Inject
    public KnownEntities(PeopleService people, FilmService films, PlanetService planets,
                         SpecieService species, StarshipService starships, VehicleService vehicles) {
        this.people = people;
        this.films = films;
        this.planets = planets;
        this.species = species;
        this.starships = starships;
        this.vehicles = vehicles;
    }

    /**
     * The services are lazy proxies that read their JSON in the constructor:
     * touch each one at startup, so the first entity page does not load JSON on
     * the event loop.
     */
    void warm(@Observes StartupEvent event) {
        people.getAllPeople();
        films.getAllFilms();
        planets.getAllPlanets();
        species.getAllSpecies();
        starships.getAllStarships();
        vehicles.getAllVehicles();
    }

    public boolean exists(String resource, String id) {
        if (resource == null || id == null || !CANONICAL_ID.matcher(id).matches()) {
            return false;
        }
        int n = Integer.parseInt(id);
        return switch (resource) {
            case "people" -> people.getPeopleById(n) != null;
            case "films" -> films.getFilmById(n) != null;
            case "planets" -> planets.getPlanetById(n) != null;
            case "species" -> species.getSpecieById(n) != null;
            case "starships" -> starships.getStarshipById(n) != null;
            case "vehicles" -> vehicles.getVehicleById(n) != null;
            default -> false;
        };
    }
}
