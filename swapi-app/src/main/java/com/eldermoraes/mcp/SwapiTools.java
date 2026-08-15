package com.eldermoraes.mcp;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class SwapiTools {

    public enum SwResource { PEOPLE, FILMS, PLANETS, SPECIES, STARSHIPS, VEHICLES }

    @Inject PeopleService peopleService;
    @Inject FilmService filmService;
    @Inject PlanetService planetService;
    @Inject SpecieService specieService;
    @Inject StarshipService starshipService;
    @Inject VehicleService vehicleService;
    @Inject Jsonb jsonb;

    @ConfigProperty(name = "swapi.public-base-url")
    Optional<String> publicBaseUrl;

    // Request-scoped client proxy: resolves to the MCP request active at the
    // moment of the tool call (the HTTP transport activates the CDI request context).
    @Inject HttpServerRequest request;

    // The services build URLs as baseUrl + path; REST discovers it via UriInfo per
    // request. Here the explicit config wins (operational escape hatch); without it,
    // the domain comes from the request itself - no hardcoded domain in the binary.
    private String resolveBaseUrl() {
        if (publicBaseUrl.isPresent()) {
            return publicBaseUrl.get();
        }
        try {
            return request.scheme() + "://" + request.host() + "/api";
        } catch (RuntimeException e) {
            throw new ToolCallException("Cannot resolve public base URL: "
                    + "no active HTTP request and swapi.public-base-url is not set");
        }
    }

    private void applyBaseUrl() {
        RequestBaseUrl.set(resolveBaseUrl());
    }

    @Tool(description = "Lists all entities of a Star Wars resource type from swapi.build. "
            + "Returns a JSON array.",
          annotations = @Tool.Annotations(title = "List Star Wars resources",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_list(
            @ToolArg(description = "Resource type") SwResource resource) {
        applyBaseUrl();
        return jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getAllPeople();
            case FILMS -> filmService.getAllFilms();
            case PLANETS -> planetService.getAllPlanets();
            case SPECIES -> specieService.getAllSpecies();
            case STARSHIPS -> starshipService.getAllStarships();
            case VEHICLES -> vehicleService.getAllVehicles();
        });
    }

    @Tool(description = "Gets one Star Wars entity by numeric id. Ids are the record ids "
            + "from each entity's url field (e.g. FILMS id 1 = A New Hope). Returns a JSON object.",
          annotations = @Tool.Annotations(title = "Get Star Wars entity by id",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_get(
            @ToolArg(description = "Resource type") SwResource resource,
            @ToolArg(description = "Numeric record id (from the entity's url field)") int id) {
        applyBaseUrl();
        Object result = switch (resource) {
            case PEOPLE -> peopleService.getPeopleById(id);
            case FILMS -> filmService.getFilmById(id);
            case PLANETS -> planetService.getPlanetById(id);
            case SPECIES -> specieService.getSpecieById(id);
            case STARSHIPS -> starshipService.getStarshipById(id);
            case VEHICLES -> vehicleService.getVehicleById(id);
        };
        if (result == null) {
            throw new ToolCallException("No " + resource.name().toLowerCase()
                    + " found with id " + id);
        }
        return jsonb.toJson(result);
    }

    @Tool(description = "Returns one random Star Wars entity of the given resource type. "
            + "Great for live demos. Returns a JSON object.",
          annotations = @Tool.Annotations(title = "Random Star Wars entity",
                  readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public String sw_random(
            @ToolArg(description = "Resource type") SwResource resource) {
        applyBaseUrl();
        return jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getRandomPeople();
            case FILMS -> filmService.getRandomFilm();
            case PLANETS -> planetService.getRandomPlanet();
            case SPECIES -> specieService.getRandomSpecie();
            case STARSHIPS -> starshipService.getRandomStarship();
            case VEHICLES -> vehicleService.getRandomVehicle();
        });
    }

    @Tool(description = "Searches a Star Wars resource by name (title for FILMS), "
            + "case-insensitive substring match. Returns a JSON array.",
          annotations = @Tool.Annotations(title = "Search Star Wars resources",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_search(
            @ToolArg(description = "Resource type") SwResource resource,
            @ToolArg(description = "Name/title fragment") String query) {
        applyBaseUrl();
        return jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getPeopleByName(query);
            case FILMS -> filmService.getFilmByTitle(query);
            case PLANETS -> planetService.getPlanetByName(query);
            case SPECIES -> specieService.getSpecieByName(query);
            case STARSHIPS -> starshipService.getStarshipByName(query);
            case VEHICLES -> vehicleService.getVehicleByName(query);
        });
    }
}
