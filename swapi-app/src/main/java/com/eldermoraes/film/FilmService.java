package com.eldermoraes.film;

import com.eldermoraes.DataFile;
import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Type;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class FilmService implements SWService {

    private static List<Film> filmList;

    public FilmService() {
        if (filmList == null) {
            loadJsonData();
        }
    }

    @Override
    public void loadJsonData() {
        Type listType = new ArrayList<Film>(){}.getClass().getGenericSuperclass();
        filmList = DataFile.load("data/films.json", listType);
    }

    public List<Film> getAllFilms() {
        return filmList;
    }

    public Film getFilmById(int id) {
        if (filmList == null) {
            return null;
        }
        String suffix = "/films/" + id;
        return filmList.stream()
                .filter(f -> f.getUrl() != null && f.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    public List<Film> getFilmByTitle(String title) {
        return filmList.stream()
                .filter(film -> film.getTitle().toLowerCase().contains(title.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Film getRandomFilm() {
        if (filmList == null || filmList.isEmpty()) {
            return null;
        }
        return filmList.get(ThreadLocalRandom.current().nextInt(filmList.size()));
    }
}
