package com.eldermoraes.film;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

@Path("/films")
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
    @Path("{episodeId}")
    public Response getFilmByEpisodeId(@PathParam("episodeId") int episodeId){
        return Response.accepted().entity(filmService.getFilmByEpisodeId(episodeId)).build();
    }

}
