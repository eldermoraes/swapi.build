package com.eldermoraes.vehicle;

import com.eldermoraes.DataFile;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class VehicleService {

    private static List<Vehicle> vehicleList;

    public VehicleService() {
        if (vehicleList == null) {
            loadJsonData();
        }
    }

    private static void loadJsonData() {
        Type listType = new ArrayList<Vehicle>(){}.getClass().getGenericSuperclass();
        vehicleList = DataFile.load("data/vehicles.json", listType);
    }

    public List<Vehicle> getAllVehicles() {
        return vehicleList;
    }

    public List<Vehicle> getVehicleByName(String name) {
        return vehicleList.stream()
                .filter(vehicle -> vehicle.getName().toLowerCase().contains(name.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Vehicle getVehicleById(int id) {
        if (vehicleList == null) {
            return null;
        }
        String suffix = "/vehicles/" + id;
        return vehicleList.stream()
                .filter(v -> v.getUrl() != null && v.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    public Vehicle getRandomVehicle() {
        if (vehicleList == null || vehicleList.isEmpty()) {
            return null;
        }
        return vehicleList.get(ThreadLocalRandom.current().nextInt(vehicleList.size()));
    }
}
