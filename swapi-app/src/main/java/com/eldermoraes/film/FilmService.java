package com.eldermoraes.film;

import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Type;

@ApplicationScoped
public class FilmService implements SWService {

    private static List<Film> filmList;

    public FilmService() {
        if (filmList == null) {
            loadJsonData();
        }
    }

    @Override
    public void setBaseUrl(String baseUrl) {
        if (filmList != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            filmList.forEach(film -> film.setBaseUrl(cleanUrl));
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("films.json")) {

            if (is == null) {
                System.err.println("Could not find films.json in resources");
                filmList = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<Film>(){}.getClass().getGenericSuperclass();
            filmList = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            filmList = new ArrayList<>();
        }
    }

    public List<Film> getAllFilms() {
        return filmList;
    }

    public Film getFilmByEpisodeId(int episodeId) {
        return filmList.stream()
                .filter(film -> film.getEpisode_id() == episodeId)
                .findFirst()
                .orElse(null);
    }

    public Film getFilmByTitle(String title) {
        return filmList.stream()
                .filter(film -> film.getTitle().toLowerCase().contains(title.toLowerCase()))
                .findFirst()
                .orElse(null);
    }
}
