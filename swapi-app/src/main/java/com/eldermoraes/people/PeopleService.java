package com.eldermoraes.people;

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
public class PeopleService implements SWService {

    private static List<People> peopleList;

    public PeopleService() {
        if (peopleList == null) {
            loadJsonData();
        }
    }

    @Override
    public void setBaseUrl(String baseUrl) {
        if (peopleList != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            peopleList.forEach(people -> people.setBaseUrl(cleanUrl));
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("people.json")) {

            if (is == null) {
                System.err.println("Could not find people.json in resources");
                peopleList = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<People>(){}.getClass().getGenericSuperclass();
            peopleList = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            peopleList = new ArrayList<>();
        }
    }

    public List<People> getAllPeople() {
        return peopleList;
    }

    public People getPeopleByName(String name) {
        return peopleList.stream()
                .filter(people -> people.getName().toLowerCase().contains(name.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    public People getPeopleById(int id) {
        return peopleList.get(id - 1);
    }

}
