package com.eldermoraes.planet;


import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
@Path("planets")
@RunOnVirtualThread
@Tag(name = "Planets", description = "Planets in the Star Wars universe")
public class PlanetResource {

    private final PlanetService planetService;

    PlanetResource(PlanetService planetService) {
        this.planetService = planetService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all planets",
            description = "Returns every planet, or only those whose name matches the search query.")
    @APIResponse(responseCode = "200", description = "List of planets",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Planet.class)))
    public Response getAllPlanets(
            @Parameter(description = "Filter by name (case-insensitive contains)", example = "tatooine")
            @QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(planetService.getPlanetByName(search)).build();
        } else {
            return Response.ok().entity(planetService.getAllPlanets()).build();
        }

    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get a planet by id")
    @APIResponse(responseCode = "200", description = "The planet with the given id",
            content = @Content(schema = @Schema(implementation = Planet.class)))
    @APIResponse(responseCode = "404", description = "No planet exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getPlanetById(
            @Parameter(description = "Numeric id of the planet", example = "1")
            @PathParam("id") int id) {
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
    @Operation(summary = "Get a random planet")
    @APIResponse(responseCode = "200", description = "A randomly selected planet",
            content = @Content(schema = @Schema(implementation = Planet.class)))
    public Response getRandomPlanet() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(planetService.getRandomPlanet()).build();
    }
}
