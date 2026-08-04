package com.eldermoraes.swapi.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The swapi.build REST API as a Java interface. Quarkus generates the HTTP calls;
 * the base URL comes from application.properties, keyed by configKey.
 *
 * The methods return String because this example hands the JSON straight back.
 * Return a record instead and Quarkus will deserialize into it.
 *
 * @Produces sets the Accept header. Without it a String return type asks for
 * text/plain and swapi.build answers 406, because it only serves JSON.
 */
@RegisterRestClient(configKey = "swapi-api")
@Produces(MediaType.APPLICATION_JSON)
public interface SwapiClient {

    @GET
    @Path("/people/{id}")
    String person(@PathParam("id") int id);

    @GET
    @Path("/people")
    String searchPeople(@QueryParam("search") String name);
}
