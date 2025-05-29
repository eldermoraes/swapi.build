package com.eldermoraes;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("films")
public class FilmResource {

    @Inject
    FilmService filmService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Film> getFilms() {
        return filmService.getAllFilms();
    }
}
