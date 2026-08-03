package com.eldermoraes.swapi.assistant.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The REST path's plumbing: one interface per remote shape. The MCP path needs
 * none of this — that is the comparison the example is built to show.
 */
@RegisterRestClient(configKey = "swapi-api")
@Produces(MediaType.APPLICATION_JSON)
public interface SwapiClient {

    @GET
    @Path("/people")
    String searchPeople(@QueryParam("search") String name);

    @GET
    @Path("/people/{id}")
    String person(@PathParam("id") int id);

    @GET
    @Path("/planets")
    String searchPlanets(@QueryParam("search") String name);

    @GET
    @Path("/planets/{id}")
    String planet(@PathParam("id") int id);
}
