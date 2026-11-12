package com.eldermoraes.specie;

import com.eldermoraes.DataFile;
import com.eldermoraes.SWService;
import com.eldermoraes.planet.Planet;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class SpecieService implements SWService {

    private static List<Specie> specieList;

    public SpecieService() {
        if (specieList == null) {
            loadJsonData();
        }
    }

    @Override
    public void loadJsonData() {
        Type listType = new ArrayList<Specie>(){}.getClass().getGenericSuperclass();
        specieList = DataFile.load("data/species.json", listType);
    }

    public List<Specie> getAllSpecies() {
        return specieList;
    }

    public List<Specie> getSpecieByName(String name) {
        return specieList.stream()
                .filter(specie -> specie.getName().toLowerCase().contains(name.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Specie getSpecieById(int id) {
        if (specieList == null) {
            return null;
        }
        String suffix = "/species/" + id;
        return specieList.stream()
                .filter(s -> s.getUrl() != null && s.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    public Specie getRandomSpecie() {
        if (specieList == null || specieList.isEmpty()) {
            return null;
        }
        return specieList.get(ThreadLocalRandom.current().nextInt(specieList.size()));
    }
}
