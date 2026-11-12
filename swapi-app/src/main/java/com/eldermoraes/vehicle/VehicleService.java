package com.eldermoraes.vehicle;

import com.eldermoraes.RecordService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;

@ApplicationScoped
public class VehicleService extends RecordService<Vehicle> {

    public VehicleService() {
        super("vehicles", new ArrayList<Vehicle>(){}.getClass().getGenericSuperclass(), Vehicle::getName, Vehicle::getUrl);
    }
}
