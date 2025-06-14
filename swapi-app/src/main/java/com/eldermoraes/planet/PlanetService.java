package com.eldermoraes.planet;

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
public class PlanetService  implements SWService {

    private static List<Planet> planetList;

    public PlanetService() {
        if (planetList == null) {
            loadJsonData();
        }
    }

    @Override
    public void setBaseUrl(String baseUrl) {
        if (planetList != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            planetList.forEach(planet -> planet.setBaseUrl(cleanUrl));
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        URL url = getClass().getResource("/data/planets.json");
        if (url != null) {
            try (Jsonb jsonb = JsonbBuilder.create(config);

                 InputStream is = url.openStream()) {

                if (is == null) {
                    System.err.println("Could not get data from planet.json");
                    planetList = new ArrayList<>();
                    return;
                }

                Type listType = new ArrayList<Planet>(){}.getClass().getGenericSuperclass();
                planetList = jsonb.fromJson(is, listType);

            } catch (Exception e) {
                System.err.println("Error loading planet: " + e.getMessage());
                planetList = new ArrayList<>();
            }
        } else{
            System.err.println("Could not find planet.json in resources");
        }
    }

    public List<Planet> getAllPlanets() {
        return planetList;
    }

    public Planet getPlanetByName(String name) {
        return planetList.stream()
                .filter(planet -> planet.getName().toLowerCase().contains(name.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    public Planet getPlanetById(int id) {
        return planetList.get(id - 1);
    }

    public Planet getRandomPlanet() {
        if (planetList == null || planetList.isEmpty()) {
            return null;
        }
        return planetList.get(ThreadLocalRandom.current().nextInt(planetList.size()));
    }
}
