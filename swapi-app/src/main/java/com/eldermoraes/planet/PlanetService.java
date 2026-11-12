package com.eldermoraes.planet;

import com.eldermoraes.DataFile;
import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Type;
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
    public void loadJsonData() {
        Type listType = new ArrayList<Planet>(){}.getClass().getGenericSuperclass();
        planetList = DataFile.load("data/planets.json", listType);
    }

    public List<Planet> getAllPlanets() {
        return planetList;
    }

    public List<Planet> getPlanetByName(String name) {
        return planetList.stream()
                .filter(planet -> planet.getName().toLowerCase().contains(name.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Planet getPlanetById(int id) {
        if (planetList == null) {
            return null;
        }
        String suffix = "/planets/" + id;
        return planetList.stream()
                .filter(p -> p.getUrl() != null && p.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    public Planet getRandomPlanet() {
        if (planetList == null || planetList.isEmpty()) {
            return null;
        }
        return planetList.get(ThreadLocalRandom.current().nextInt(planetList.size()));
    }
}
