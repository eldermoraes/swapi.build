package com.eldermoraes.specie;

import com.eldermoraes.SWService;
import com.eldermoraes.planet.Planet;
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
public class SpecieService implements SWService {

    private static List<Specie> specieList;

    public SpecieService() {
        if (specieList == null) {
            loadJsonData();
        }
    }

    @Override
    public void setBaseUrl(String baseUrl) {
        if (specieList != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            specieList.forEach(specie -> specie.setBaseUrl(cleanUrl));
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        URL url = getClass().getResource("/data/species.json");
        if (url != null) {
            try (Jsonb jsonb = JsonbBuilder.create(config);

                 InputStream is = url.openStream()) {

                if (is == null) {
                    System.err.println("Could not get data from species.json");
                    specieList = new ArrayList<>();
                    return;
                }

                Type listType = new ArrayList<Specie>(){}.getClass().getGenericSuperclass();
                specieList = jsonb.fromJson(is, listType);

            } catch (Exception e) {
                System.err.println("Error loading species: " + e.getMessage());
                specieList = new ArrayList<>();
            }
        } else{
            System.err.println("Could not find species.json in resources");
        }
    }

    public List<Specie> getAllSpecies() {
        return specieList;
    }

    public Specie getSpecieByName(String name) {
        return specieList.stream()
                .filter(specie -> specie.getName().toLowerCase().contains(name.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    public Specie getSpecieById(int id) {
        return specieList.get(id - 1);
    }

    public Specie getRandomSpecie() {
        if (specieList == null || specieList.isEmpty()) {
            return null;
        }
        return specieList.get(ThreadLocalRandom.current().nextInt(specieList.size()));
    }
}
