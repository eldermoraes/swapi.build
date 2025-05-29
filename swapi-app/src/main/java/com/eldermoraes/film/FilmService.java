package com.eldermoraes.film;

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

    private static List<Film> FILMS;

    public FilmService() {
        if (FILMS == null) {
            loadFilms();
        }
    }

    public void setBaseUrl(String baseUrl) {
        if (FILMS != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            FILMS.forEach(film -> film.setBaseUrl(cleanUrl));
        }
    }

    private void loadFilms() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("films.json")) {

            if (is == null) {
                System.err.println("Could not find films.json in resources");
                FILMS = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<Film>(){}.getClass().getGenericSuperclass();
            FILMS = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            FILMS = new ArrayList<>();
        }
    }

    public List<Film> getAllFilms() {
        return FILMS;
    }

    public Film getFilmByEpisodeId(int episodeId) {
        return FILMS.stream()
                .filter(film -> film.getEpisode_id() == episodeId)
                .findFirst()
                .orElse(null);
    }

    public Film getFilmByTitle(String title) {
        return FILMS.stream()
                .filter(film -> film.getTitle().toLowerCase().contains(title.toLowerCase()))
                .findFirst()
                .orElse(null);
    }
}
