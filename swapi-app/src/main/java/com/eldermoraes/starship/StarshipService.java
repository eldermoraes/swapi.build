package com.eldermoraes.starship;

import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class StarshipService implements SWService {

    private static List<Starship> starshipList;

    public StarshipService() {
        if (starshipList == null) {
            loadJsonData();
        }
    }

    @Override
    public void setBaseUrl(String baseUrl) {
        if (starshipList != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            starshipList.forEach(starship -> starship.setBaseUrl(cleanUrl));
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("starships.json")) {

            if (is == null) {
                System.err.println("Could not find starships.json in resources");
                starshipList = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<Starship>(){}.getClass().getGenericSuperclass();
            starshipList = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            starshipList = new ArrayList<>();
        }
    }

    public List<Starship> getAllStarships() {
        return starshipList;
    }

    public Starship getStarshipByName(String name) {
        return starshipList.stream()
                .filter(starship -> starship.getName().toLowerCase().contains(name.toLowerCase()))
                .findFirst()
                .orElse(null);
    }
}
