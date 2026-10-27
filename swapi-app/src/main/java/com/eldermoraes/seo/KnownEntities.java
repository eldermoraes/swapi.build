package com.eldermoraes.seo;

import com.eldermoraes.SWObject;
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
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** The six record types, as in /resource/{type} and /api/{type}. */
    public static final Set<String> TYPES = Set.of("people", "films", "planets", "species", "starships", "vehicles");

    private static final Set<String> FILM_FIELDS = Set.of("title", "episode_id", "director", "release_date");
    private static final Set<String> NAME_FIELD = Set.of("name");

    /** The fields of a type that a metadata template (seo/site.json, detail.fields) may use. */
    public static Set<String> supportedFields(String type) {
        if ("films".equals(type)) {
            return FILM_FIELDS;
        }
        return TYPES.contains(type) ? NAME_FIELD : Set.of();
    }

    /**
     * The template fields of a record that exists (supportedFields), as text;
     * numbers in plain decimal. Explicit getters, no reflection, and none reads
     * getUrl(). Empty for a record that does not exist (same rule as exists). A
     * null value is left out, so the template falls back instead of saying "null".
     */
    public Optional<Map<String, String>> fields(String type, String id) {
        if (!exists(type, id)) {
            return Optional.empty();
        }
        int n = Integer.parseInt(id);
        Map<String, String> values = new HashMap<>();
        switch (type) {
            case "films" -> {
                Film film = films.getFilmById(n);
                put(values, "title", film.getTitle());
                put(values, "episode_id", Integer.toString(film.getEpisode_id()));
                put(values, "director", film.getDirector());
                put(values, "release_date", film.getRelease_date());
            }
            case "people" -> put(values, "name", people.getPeopleById(n).getName());
            case "planets" -> put(values, "name", planets.getPlanetById(n).getName());
            case "species" -> put(values, "name", species.getSpecieById(n).getName());
            case "starships" -> put(values, "name", starships.getStarshipById(n).getName());
            case "vehicles" -> put(values, "name", vehicles.getVehicleById(n).getName());
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(Map.copyOf(values));
    }

    /**
     * The ids of a type, ascending, taken from the suffix of getUrl() (only the
     * suffix, as in exists: the base the event loop may hold is never used). A
     * record added to the data is listed here with no code change.
     */
    public List<Integer> ids(String type) {
        List<? extends SWObject> all = switch (type == null ? "" : type) {
            case "people" -> people.getAllPeople();
            case "films" -> films.getAllFilms();
            case "planets" -> planets.getAllPlanets();
            case "species" -> species.getAllSpecies();
            case "starships" -> starships.getAllStarships();
            case "vehicles" -> vehicles.getAllVehicles();
            default -> List.of();
        };
        if (all == null) {
            return List.of();
        }
        String prefix = "/" + type + "/";
        List<Integer> ids = new ArrayList<>();
        for (SWObject entity : all) {
            String url = url(entity);
            int at = url == null ? -1 : url.lastIndexOf(prefix);
            if (at < 0) {
                continue;
            }
            String id = url.substring(at + prefix.length());
            if (CANONICAL_ID.matcher(id).matches()) {
                ids.add(Integer.parseInt(id));
            }
        }
        ids.sort(null);
        return List.copyOf(ids);
    }

    private static String url(SWObject entity) {
        return switch (entity) {
            case People p -> p.getUrl();
            case Film f -> f.getUrl();
            case Planet p -> p.getUrl();
            case Specie s -> s.getUrl();
            case Starship s -> s.getUrl();
            case Vehicle v -> v.getUrl();
            default -> null;
        };
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }
}
