package com.eldermoraes.film;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

@Path("/films")
public class FilmResource {

    private final String hostUrl;

    FilmService filmService;
    UriInfo uriInfo;

    FilmResource(UriInfo uriInfo, FilmService filmService){
        this.uriInfo = uriInfo;
        hostUrl = this.uriInfo.getBaseUri().toString();

        this.filmService = filmService;
        this.filmService.setBaseUrl(hostUrl);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Film> getAllFilms() {
        return filmService.getAllFilms();
    }
}
