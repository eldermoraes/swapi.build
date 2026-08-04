package com.eldermoraes.swapi.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The swapi.build REST API as a Java interface. Quarkus generates the HTTP calls;
 * the base URL comes from application.properties, keyed by configKey.
 *
 * The methods return Response so the upstream status code travels with the body:
 * a 404 from swapi.build stays a 404, with no error-mapping code (paired with
 * the disable.default.mapper line in application.properties). Return a record
 * instead and Quarkus will deserialize the JSON into it.
 *
 * The @Produces annotation sets the Accept header of the outgoing request,
 * stating on the interface that this API serves JSON.
 */
@RegisterRestClient(configKey = "swapi-api")
@Produces(MediaType.APPLICATION_JSON)
public interface SwapiClient {

    @GET
    @Path("/people/{id}")
    Response person(@PathParam("id") int id);

    @GET
    @Path("/people")
    Response searchPeople(@QueryParam("search") String name);
}
