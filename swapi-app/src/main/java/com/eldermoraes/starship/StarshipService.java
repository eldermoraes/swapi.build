package com.eldermoraes.starship;

import com.eldermoraes.DataFile;
import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Type;
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
    public void loadJsonData() {
        Type listType = new ArrayList<Starship>(){}.getClass().getGenericSuperclass();
        starshipList = DataFile.load("data/starships.json", listType);
    }

    public List<Starship> getAllStarships() {
        return starshipList;
    }

    public List<Starship> getStarshipByName(String name) {
        return starshipList.stream()
                .filter(starship -> starship.getName().toLowerCase().contains(name.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Starship getStarshipById(int id) {
        if (starshipList == null) {
            return null;
        }
        String suffix = "/starships/" + id;
        return starshipList.stream()
                .filter(s -> s.getUrl() != null && s.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    public Starship getRandomStarship() {
        if (starshipList == null || starshipList.isEmpty()) {
            return null;
        }
        return starshipList.get(ThreadLocalRandom.current().nextInt(starshipList.size()));
    }
}
