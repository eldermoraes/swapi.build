package com.eldermoraes.people;

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
@Path("people")
@RunOnVirtualThread
@Tag(name = "People", description = "People within the Star Wars universe")
public class PeopleResource {

    private final PeopleService peopleService;

    PeopleResource(PeopleService peopleService) {
        this.peopleService = peopleService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all people",
            description = "Returns every person, or only those whose name matches the search query.")
    @APIResponse(responseCode = "200", description = "List of people",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = People.class)))
    public Response getAllPeople(
            @Parameter(description = "Filter by name (case-insensitive contains)", example = "luke")
            @QueryParam("search") String search) {
        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(peopleService.getPeopleByName(search)).build();
        } else {
            return Response.ok().entity(peopleService.getAllPeople()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @Operation(summary = "Get a person by id")
    @APIResponse(responseCode = "200", description = "The person with the given id",
            content = @Content(schema = @Schema(implementation = People.class)))
    @APIResponse(responseCode = "404", description = "No person exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getPeopleById(
            @Parameter(description = "Numeric id of the person", example = "1")
            @PathParam("id") int id) {
        People people = peopleService.getPeopleById(id);
        if (people == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No people found with id " + id).build();
        }
        return Response.ok().entity(people).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    @Operation(summary = "Get a random person")
    @APIResponse(responseCode = "200", description = "A randomly selected person",
            content = @Content(schema = @Schema(implementation = People.class)))
    public Response getRandomPeople() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(peopleService.getRandomPeople()).build();
    }
}
