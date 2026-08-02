package com.eldermoraes.specie;

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
@Path("species")
@RunOnVirtualThread
@Tag(name = "Species", description = "Species in the Star Wars universe")
public class SpecieResource {

    private final SpecieService specieService;

    SpecieResource(SpecieService specieService) {
        this.specieService = specieService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all species",
            description = "Returns every species, or only those whose name matches the search query.")
    @APIResponse(responseCode = "200", description = "List of species",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Specie.class)))
    public Response getSpecies(
            @Parameter(description = "Filter by name (case-insensitive contains)", example = "wookiee")
            @QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(specieService.getSpecieByName(search)).build();
        } else {
            return Response.ok().entity(specieService.getAllSpecies()).build();
        }

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @Operation(summary = "Get a species by id")
    @APIResponse(responseCode = "200", description = "The species with the given id",
            content = @Content(schema = @Schema(implementation = Specie.class)))
    @APIResponse(responseCode = "404", description = "No species exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getSpecieById(
            @Parameter(description = "Numeric id of the species", example = "1")
            @PathParam("id") int id) {
        Specie specie = specieService.getSpecieById(id);
        if (specie == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No specie found with id " + id).build();
        }
        return Response.ok().entity(specie).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    @Operation(summary = "Get a random species")
    @APIResponse(responseCode = "200", description = "A randomly selected species",
            content = @Content(schema = @Schema(implementation = Specie.class)))
    public Response getRandomSpecie() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(specieService.getRandomSpecie()).build();
    }

}
