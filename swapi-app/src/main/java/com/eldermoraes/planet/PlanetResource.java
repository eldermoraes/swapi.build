package com.eldermoraes.planet;


import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Path("planets")
@RunOnVirtualThread
public class PlanetResource {

    private final PlanetService planetService;

    PlanetResource(PlanetService planetService) {
        this.planetService = planetService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllPlanets(@QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(planetService.getPlanetByName(search)).build();
        } else {
            return Response.ok().entity(planetService.getAllPlanets()).build();
        }

    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPlanetById(@PathParam("id") int id) {
        Planet planet = planetService.getPlanetById(id);
        if (planet == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No planet found with id " + id).build();
        }
        return Response.ok().entity(planet).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomPlanet() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(planetService.getRandomPlanet()).build();
    }
}
