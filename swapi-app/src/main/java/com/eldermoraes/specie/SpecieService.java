package com.eldermoraes.specie;

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
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("species.json")) {

            if (is == null) {
                System.err.println("Could not find species.json in resources");
                specieList = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<Specie>(){}.getClass().getGenericSuperclass();
            specieList = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            specieList = new ArrayList<>();
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
}
