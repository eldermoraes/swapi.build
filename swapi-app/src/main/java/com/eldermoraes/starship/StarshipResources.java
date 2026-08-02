package com.eldermoraes.starship;

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
@Path("starships")
@RunOnVirtualThread
@Tag(name = "Starships", description = "Starships in the Star Wars universe")
public class StarshipResources {

    private final StarshipService starshipService;

    StarshipResources(StarshipService starshipService) {
        this.starshipService = starshipService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all starships",
            description = "Returns every starship, or only those whose name matches the search query.")
    @APIResponse(responseCode = "200", description = "List of starships",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Starship.class)))
    public Response getStarships(
            @Parameter(description = "Filter by name (case-insensitive contains)", example = "falcon")
            @QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(starshipService.getStarshipByName(search)).build();
        } else {
            return Response.ok().entity(starshipService.getAllStarships()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @Operation(summary = "Get a starship by id")
    @APIResponse(responseCode = "200", description = "The starship with the given id",
            content = @Content(schema = @Schema(implementation = Starship.class)))
    @APIResponse(responseCode = "404", description = "No starship exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getStarshipById(
            @Parameter(description = "Numeric id of the starship", example = "2")
            @PathParam("id") int id) {
        Starship starship = starshipService.getStarshipById(id);
        if (starship == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No starship found with id " + id).build();
        }
        return Response.ok().entity(starship).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    @Operation(summary = "Get a random starship")
    @APIResponse(responseCode = "200", description = "A randomly selected starship",
            content = @Content(schema = @Schema(implementation = Starship.class)))
    public Response getRandomStarship() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(starshipService.getRandomStarship()).build();
    }

}
