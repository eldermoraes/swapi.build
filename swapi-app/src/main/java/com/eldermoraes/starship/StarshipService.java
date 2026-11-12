package com.eldermoraes.starship;

import com.eldermoraes.RecordService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class StarshipService extends RecordService<Starship> {

    public StarshipService() {
        super("starships", new ArrayList<Starship>(){}.getClass().getGenericSuperclass(), Starship::getName, Starship::getUrl);
    }
}
