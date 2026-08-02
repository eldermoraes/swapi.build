package com.eldermoraes.film;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
@Path("/films")
@RunOnVirtualThread
@Tag(name = "Films", description = "Star Wars films")
public class FilmResource {

    private final FilmService filmService;

    FilmResource(FilmService filmService){
        this.filmService = filmService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all films",
            description = "Returns every film, or only those whose title matches the search query.")
    @APIResponse(responseCode = "200", description = "List of films",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Film.class)))
    public Response getAllFilms(
            @Parameter(description = "Filter by title (case-insensitive contains)", example = "hope")
            @QueryParam("search") String search) {

        if (search != null && !search.isEmpty()) {
            return Response.ok().entity(filmService.getFilmByTitle(search)).build();
        } else{
            return Response.ok().entity(filmService.getAllFilms()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @Operation(summary = "Get a film by id")
    @APIResponse(responseCode = "200", description = "The film with the given id",
            content = @Content(schema = @Schema(implementation = Film.class)))
    @APIResponse(responseCode = "404", description = "No film exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getFilmById(
            @Parameter(description = "Numeric id of the film", example = "1")
            @PathParam("id") int id){
        Film film = filmService.getFilmById(id);
        if (film == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No film found with id " + id).build();
        }
        return Response.ok().entity(film).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    @Operation(summary = "Get a random film")
    @APIResponse(responseCode = "200", description = "A randomly selected film",
            content = @Content(schema = @Schema(implementation = Film.class)))
    public Response getRandomFilm() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.ok().entity(filmService.getRandomFilm()).build();
    }

}
