package com.eldermoraes.starship;

import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
        URL url = getClass().getResource("/data/starships.json");
        if (url != null) {
            try (Jsonb jsonb = JsonbBuilder.create(config);

                 InputStream is = url.openStream()) {

                if (is == null) {
                    System.err.println("Could not get data from starships.json");
                    starshipList = new ArrayList<>();
                    return;
                }

                Type listType = new ArrayList<Starship>(){}.getClass().getGenericSuperclass();
                starshipList = jsonb.fromJson(is, listType);

            } catch (Exception e) {
                System.err.println("Error loading starships: " + e.getMessage());
                starshipList = new ArrayList<>();
            }
        } else{
            System.err.println("Could not find starships.json in resources");
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

    public Starship getStarshipById(int id) {
        return starshipList.get(id - 1);
    }

    public Starship getRandomStarship() {
        if (starshipList == null || starshipList.isEmpty()) {
            return null;
        }
        return starshipList.get(ThreadLocalRandom.current().nextInt(starshipList.size()));
    }
}
