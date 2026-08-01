package com.eldermoraes.film;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

@RequestScoped
@Path("/films")
@RunOnVirtualThread
public class FilmResource {

    private final FilmService filmService;

    FilmResource(UriInfo uriInfo, FilmService filmService){
        this.filmService = filmService;
        this.filmService.setBaseUrl(uriInfo.getBaseUri().toString());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllFilms(@QueryParam("search") String search) {

        if (search != null && !search.isEmpty()) {
            return Response.accepted().entity(filmService.getFilmByTitle(search)).build();
        } else{
            return Response.accepted().entity(filmService.getAllFilms()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getFilmById(@PathParam("id") int id){
        Film film = filmService.getFilmById(id);
        if (film == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No film found with id " + id).build();
        }
        return Response.accepted().entity(film).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("random")
    public Response getRandomFilm() {
        Log.info("Thread name: " + Thread.currentThread().getName());
        return Response.accepted().entity(filmService.getRandomFilm()).build();
    }

}
