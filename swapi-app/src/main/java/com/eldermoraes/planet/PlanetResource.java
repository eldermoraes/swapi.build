package com.eldermoraes.planet;


import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("planets")
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
}
