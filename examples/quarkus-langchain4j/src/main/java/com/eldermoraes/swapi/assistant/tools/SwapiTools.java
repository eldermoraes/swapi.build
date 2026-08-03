package com.eldermoraes.swapi.assistant.tools;

import com.eldermoraes.swapi.assistant.client.SwapiClient;
import dev.langchain4j.agent.tool.Tool;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
        return client.person(id);
    }

    @Tool("Search Star Wars planets by name. Returns a JSON array.")
    @RunOnVirtualThread
    public String searchPlanets(String name) {
        return client.searchPlanets(name);
    }

    @Tool("Get one Star Wars planet by numeric id, including its climate and "
            + "surface temperature hints. Returns a JSON object.")
    @RunOnVirtualThread
    public String planet(int id) {
        return client.planet(id);
    }
}
