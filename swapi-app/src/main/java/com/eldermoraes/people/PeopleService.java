package com.eldermoraes.people;

import com.eldermoraes.RecordService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class PeopleService extends RecordService<People> {

    public PeopleService() {
        super("people", new ArrayList<People>(){}.getClass().getGenericSuperclass(), People::getName, People::getUrl);
    }
}
