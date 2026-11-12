package com.eldermoraes.specie;

import com.eldermoraes.RecordService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class SpecieService extends RecordService<Specie> {

    public SpecieService() {
        super("species", new ArrayList<Specie>(){}.getClass().getGenericSuperclass(), Specie::getName, Specie::getUrl);
    }
}
