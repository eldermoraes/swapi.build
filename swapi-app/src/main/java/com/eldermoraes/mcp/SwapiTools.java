package com.eldermoraes.mcp;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.SWObject;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.seo.ssr.RecordFields;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@ApplicationScoped
public class SwapiTools {

    public enum SwResource {
        PEOPLE, FILMS, PLANETS, SPECIES, STARSHIPS, VEHICLES;

        /** The type as in /api/{type}: people, films, ... */
        public String type() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // Shared by the four tools. The error is the extension's argument conversion
    // ("Invalid argument [resource] - unable to convert value"), a tool error.
    private static final String RESOURCE_ARG = "Resource type: PEOPLE, FILMS, PLANETS, SPECIES, STARSHIPS or "
            + "VEHICLES. Any other value returns a tool error.";

    // 2.13.0: the optional fields of sw_list and sw_search.
    private static final String FIELDS_ARG = "Optional. Field names to keep in each entity (url is always kept), "
            + "e.g. [\"name\"] (FILMS: [\"title\"]). Up to 24 names; an unknown name returns a tool error listing "
            + "the valid ones. Omit for full entities.";

    // Claude Code writes a tool result over about 50 000 characters to a file and
    // hands the model only the path; this raises that limit for the three tools
    // whose answer can pass it (sw_get with expand ["*"] on a film: about 52 KB).
    private static final String MAX_RESULT_PREFIX = "anthropic/";
    private static final String MAX_RESULT_NAME = "maxResultSizeChars";
    private static final String MAX_RESULT_VALUE = "100000";

    @Inject PeopleService peopleService;
    @Inject FilmService filmService;
    @Inject PlanetService planetService;
    @Inject SpecieService specieService;
    @Inject StarshipService starshipService;
    @Inject VehicleService vehicleService;
    @Inject Jsonb jsonb;
    @Inject RecordExpansion expansion;

    @ConfigProperty(name = "swapi.public-base-url")
    Optional<String> publicBaseUrl;

    // Request-scoped client proxy: resolves to the MCP request active at the
    // moment of the tool call (the HTTP transport activates the CDI request context).
    @Inject HttpServerRequest request;

    // The services build URLs as baseUrl + path. swapi.public-base-url is the base of
    // the API (https://host/api, one meaning since 2.11.0; PublicSiteUrlResolver reads
    // it the same way). Here the explicit config wins (operational escape hatch);
    // without it, the domain comes from the request itself - no hardcoded domain in the
    // binary. The REST API does not read the property at all: it builds its URLs from
    // UriInfo per request (BaseUrlFilter).
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

    /**
     * Runs body with RequestBaseUrl set to this request's base and puts back
     * whatever the thread had before, also when body throws (the pattern of
     * RecordViews). Until 2.12.1 the tools set it and never restored it, so a
     * worker thread kept the last caller's host. Tools, resources, expand and
     * fields all read the entity getters inside this block.
     */
    <T> T withBaseUrl(Supplier<T> body) {
        String base = resolveBaseUrl();
        String previous = RequestBaseUrl.get();
        RequestBaseUrl.set(base);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                RequestBaseUrl.clear();
            } else {
                RequestBaseUrl.set(previous);
            }
        }
    }

    /** The sw_get text of a record, or null when it does not exist. Call inside withBaseUrl. */
    String recordText(SwResource resource, int id) {
        SWObject entity = record(resource, id);
        return entity == null ? null : jsonb.toJson(entity);
    }

    private SWObject record(SwResource resource, int id) {
        return switch (resource) {
            case PEOPLE -> peopleService.getPeopleById(id);
            case FILMS -> filmService.getFilmById(id);
            case PLANETS -> planetService.getPlanetById(id);
            case SPECIES -> specieService.getSpecieById(id);
            case STARSHIPS -> starshipService.getStarshipById(id);
            case VEHICLES -> vehicleService.getVehicleById(id);
        };
    }

    @MetaField(prefix = MAX_RESULT_PREFIX, name = MAX_RESULT_NAME, type = MetaField.Type.INT, value = MAX_RESULT_VALUE)
    @Tool(description = "Lists every entity of one Star Wars resource type as a JSON array "
            + "(Episodes I-VI data). Responses are large (all 82 PEOPLE, about 38 KB); to find a "
            + "single entity, prefer sw_search, then sw_get; for a compact index pass fields, e.g. "
            + "[\"name\"] (about 5 KB).",
          annotations = @Tool.Annotations(title = "List Star Wars resources",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_list(
            @ToolArg(description = RESOURCE_ARG) SwResource resource,
            @ToolArg(description = FIELDS_ARG, required = false) List<String> fields) {
        Set<String> keep = expansion.fields(resource.type(), fields);
        return withBaseUrl(() -> expansion.project(jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getAllPeople();
            case FILMS -> filmService.getAllFilms();
            case PLANETS -> planetService.getAllPlanets();
            case SPECIES -> specieService.getAllSpecies();
            case STARSHIPS -> starshipService.getAllStarships();
            case VEHICLES -> vehicleService.getAllVehicles();
        }), keep));
    }

    @MetaField(prefix = MAX_RESULT_PREFIX, name = MAX_RESULT_NAME, type = MetaField.Type.INT, value = MAX_RESULT_VALUE)
    @Tool(description = "Gets one Star Wars entity by numeric record id and returns it as a JSON "
            + "object. Take the id from the end of an entity's url field (from sw_search, sw_list or "
            + "a relation URL). Ids are record ids, not episode numbers: FILMS id 1 is A New Hope "
            + "(Episode IV). An unknown id returns a tool error, e.g. 'No films found with id 99'. "
            + "Pass expand to get related records in the same call, e.g. [\"homeworld\", \"species\"]; "
            + "name only the relations you need; * on a film can pass 50 KB.",
          annotations = @Tool.Annotations(title = "Get Star Wars entity by id",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_get(
            @ToolArg(description = RESOURCE_ARG) SwResource resource,
            @ToolArg(description = "Numeric record id: the last segment of the entity's url field "
                    + "(1 in .../api/people/1).") int id,
            @ToolArg(description = "Optional. Relations to embed, one level deep, under a final \"expanded\" "
                    + "key: PEOPLE homeworld, films, species, vehicles, starships; FILMS characters, planets, starships, "
                    + "vehicles, species; PLANETS residents, films; SPECIES homeworld, people, films; STARSHIPS "
                    + "and VEHICLES pilots, films; or [\"*\"] alone for all. Up to 8 names; an unknown name "
                    + "returns a tool error.", required = false) List<String> expand) {
        List<RecordFields.Relation> relations = expansion.relations(resource.type(), expand);
        return withBaseUrl(() -> {
            SWObject result = record(resource, id);
            if (result == null) {
                throw new ToolCallException("No " + resource.type() + " found with id " + id);
            }
            return expansion.expand(resource.type(), result, jsonb.toJson(result), relations);
        });
    }

    @Tool(description = "Returns one random Star Wars entity of the given resource type as a "
            + "JSON object. Each call can return a different entity, so it is not idempotent. Useful "
            + "for demos and quizzes; to fetch a specific entity use sw_search or sw_get.",
          annotations = @Tool.Annotations(title = "Random Star Wars entity",
                  readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public String sw_random(
            @ToolArg(description = RESOURCE_ARG) SwResource resource) {
        return withBaseUrl(() -> jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getRandomPeople();
            case FILMS -> filmService.getRandomFilm();
            case PLANETS -> planetService.getRandomPlanet();
            case SPECIES -> specieService.getRandomSpecie();
            case STARSHIPS -> starshipService.getRandomStarship();
            case VEHICLES -> vehicleService.getRandomVehicle();
        }));
    }

    @MetaField(prefix = MAX_RESULT_PREFIX, name = MAX_RESULT_NAME, type = MetaField.Type.INT, value = MAX_RESULT_VALUE)
    @Tool(description = "Start here. Finds Star Wars entities by name (title for FILMS) with a "
            + "case-insensitive substring match: 'sky' finds Luke, Anakin and Shmi Skywalker. Returns "
            + "a JSON array of full entities; an empty array [] means no match; pass fields to shorten "
            + "each entity. The id to pass to sw_get is the last segment of each entity's url field. "
            + "Data covers Episodes I-VI only.",
          annotations = @Tool.Annotations(title = "Search Star Wars resources",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_search(
            @ToolArg(description = RESOURCE_ARG) SwResource resource,
            @ToolArg(description = "Name or title fragment, case-insensitive (e.g. 'falcon', 'hope').") String query,
            @ToolArg(description = FIELDS_ARG, required = false) List<String> fields) {
        Set<String> keep = expansion.fields(resource.type(), fields);
        return withBaseUrl(() -> expansion.project(jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getPeopleByName(query);
            case FILMS -> filmService.getFilmByTitle(query);
            case PLANETS -> planetService.getPlanetByName(query);
            case SPECIES -> specieService.getSpecieByName(query);
            case STARSHIPS -> starshipService.getStarshipByName(query);
            case VEHICLES -> vehicleService.getVehicleByName(query);
        }), keep));
    }
}
