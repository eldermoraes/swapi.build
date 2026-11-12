package com.eldermoraes.planet;

import com.eldermoraes.RecordService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class PlanetService extends RecordService<Planet> {

    public PlanetService() {
        super("planets", new ArrayList<Planet>(){}.getClass().getGenericSuperclass(), Planet::getName, Planet::getUrl);
    }
}
