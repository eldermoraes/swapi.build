package com.eldermoraes.swapi.restclient;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/people")
@Produces(MediaType.APPLICATION_JSON)
public class PeopleResource {

    @Inject
    @RestClient
    SwapiClient swapi;

    @GET
    @Path("/{id}")
    public Response person(@PathParam("id") int id) {
        return swapi.person(id);
    }

    @GET
    public Response search(@QueryParam("search") String name) {
        return swapi.searchPeople(name);
    }
}
