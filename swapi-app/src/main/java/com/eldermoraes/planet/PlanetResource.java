package com.eldermoraes.planet;


import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("planets")
@RunOnVirtualThread
public class PlanetResource {

    private final PlanetService planetService;

    PlanetResource(UriInfo uriInfo, PlanetService planetService) {
        this.planetService = planetService;
        this.planetService.setBaseUrl(uriInfo.getBaseUri().toString());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllPlanets(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.accepted().entity(planetService.getPlanetByName(search)).build();
        } else {
            return Response.accepted().entity(planetService.getAllPlanets()).build();
        }

    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPlanetById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            return Response.accepted().entity(planetService.getPlanetById(Integer.parseInt(id))).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }
}
