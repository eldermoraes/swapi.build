package com.eldermoraes.swapi.assistant.tools;

import com.eldermoraes.swapi.assistant.client.SwapiClient;
import dev.langchain4j.agent.tool.Tool;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class SwapiTools {

    @Inject
    @RestClient
    SwapiClient client;

    @Tool("Search Star Wars characters by name. Returns a JSON array.")
    @RunOnVirtualThread
    public String searchPeople(String name) {
        return client.searchPeople(name);
    }

    @Tool("Get one Star Wars character by numeric id. Returns a JSON object.")
    @RunOnVirtualThread
    public String person(int id) {
        try {
            return client.person(id);
        } catch (WebApplicationException e) {
            return notFoundOrRethrow(e, "no person with id " + id);
        }
    }

    @Tool("Search Star Wars planets by name. Returns a JSON array.")
    @RunOnVirtualThread
    public String searchPlanets(String name) {
        return client.searchPlanets(name);
    }

    @Tool("Get one Star Wars planet by numeric id. To find where a character lives, "
            + "take the number at the end of that person's homeworld URL: a homeworld "
            + "of \"https://swapi.build/api/planets/1\" means id 1. Returns a JSON "
            + "object with the planet's climate, terrain, gravity, diameter, "
            + "percentage of surface covered by water, and population.")
    @RunOnVirtualThread
    public String planet(int id) {
        try {
            return client.planet(id);
        } catch (WebApplicationException e) {
            return notFoundOrRethrow(e, "no planet with id " + id);
        }
    }

    /**
     * The model guesses ids, and a wrong guess returns 404, which the REST client
     * raises as an exception that would abort the whole AI call. Handing the model a
     * readable JSON error instead lets it correct itself and try again. Deliberately
     * narrow: only 404 is translated, everything else still propagates.
     */
    private static String notFoundOrRethrow(WebApplicationException e, String message) {
        if (e.getResponse() != null
                && e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            return "{\"error\":\"" + message + "\"}";
        }
        throw e;
    }
}
