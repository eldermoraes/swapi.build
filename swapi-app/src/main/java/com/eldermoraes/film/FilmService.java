package com.eldermoraes.film;

import com.eldermoraes.RecordService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class FilmService extends RecordService<Film> {

    public FilmService() {
        super("films", new ArrayList<Film>(){}.getClass().getGenericSuperclass(), Film::getTitle, Film::getUrl);
    }
}
