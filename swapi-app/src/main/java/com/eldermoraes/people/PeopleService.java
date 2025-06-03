package com.eldermoraes.people;

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
        URL url = getClass().getResource("/data/people.json");
        if (url != null) {
            try (Jsonb jsonb = JsonbBuilder.create(config);

                 InputStream is = url.openStream()) {

                if (is == null) {
                    System.err.println("Could not get data from people.json");
                    peopleList = new ArrayList<>();
                    return;
                }

                Type listType = new ArrayList<People>(){}.getClass().getGenericSuperclass();
                peopleList = jsonb.fromJson(is, listType);

            } catch (Exception e) {
                System.err.println("Error loading people: " + e.getMessage());
                peopleList = new ArrayList<>();
            }
        } else{
            System.err.println("Could not find people.json in resources");
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
        if (peopleList == null || id < 1 || id > peopleList.size()) {
            return null;
        }
        return peopleList.get(id - 1);
    }

    public People getRandomPeople() {
        if (peopleList == null || peopleList.isEmpty()) {
            return null;
        }
        return peopleList.get(ThreadLocalRandom.current().nextInt(peopleList.size()));
    }
}
