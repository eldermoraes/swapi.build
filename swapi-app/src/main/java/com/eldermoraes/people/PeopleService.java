package com.eldermoraes.people;

import com.eldermoraes.DataFile;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


@ApplicationScoped
public class PeopleService {

    private static List<People> peopleList;

    public PeopleService() {
        if (peopleList == null) {
            loadJsonData();
        }
    }

    private static void loadJsonData() {
        Type listType = new ArrayList<People>(){}.getClass().getGenericSuperclass();
        peopleList = DataFile.load("data/people.json", listType);
    }

    public List<People> getAllPeople() {
        return peopleList;
    }

    public List<People> getPeopleByName(String name) {
        return peopleList.stream()
                .filter(people -> people.getName().toLowerCase().contains(name.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    public People getPeopleById(int id) {
        if (peopleList == null) {
            return null;
        }
        String suffix = "/people/" + id;
        return peopleList.stream()
                .filter(p -> p.getUrl() != null && p.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    public People getRandomPeople() {
        if (peopleList == null || peopleList.isEmpty()) {
            return null;
        }
        return peopleList.get(ThreadLocalRandom.current().nextInt(peopleList.size()));
    }
}
