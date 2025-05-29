package com.eldermoraes;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Type;

@ApplicationScoped
public class FilmService {

    private List<Film> films;

    public FilmService() {
        loadFilms();
    }

    private void loadFilms() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("films.json")) {

            if (is == null) {
                System.err.println("Could not find films.json in resources");
                films = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<Film>(){}.getClass().getGenericSuperclass();
            films = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            films = new ArrayList<>();
        }
    }

    public List<Film> getAllFilms() {
        return films;
    }

    public Film getFilmByEpisodeId(int episodeId) {
        return films.stream()
                .filter(film -> film.getEpisode_id() == episodeId)
                .findFirst()
                .orElse(null);
    }

    public Film getFilmByTitle(String title) {
        return films.stream()
                .filter(film -> film.getTitle().equalsIgnoreCase(title))
                .findFirst()
                .orElse(null);
    }
}
