package com.eldermoraes.vehicle;

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
public class VehicleService implements SWService {

    private static List<Vehicle> vehicleList;

    public VehicleService() {
        if (vehicleList == null) {
            loadJsonData();
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        URL url = getClass().getResource("/data/vehicles.json");
        if (url != null) {
            try (Jsonb jsonb = JsonbBuilder.create(config);

                 InputStream is = url.openStream()) {

                if (is == null) {
                    System.err.println("Could not get data from vehicles.json");
                    vehicleList = new ArrayList<>();
                    return;
                }

                Type listType = new ArrayList<Vehicle>(){}.getClass().getGenericSuperclass();
                vehicleList = jsonb.fromJson(is, listType);

            } catch (Exception e) {
                System.err.println("Error loading vehicles: " + e.getMessage());
                vehicleList = new ArrayList<>();
            }
        } else{
            System.err.println("Could not find vehicles.json in resources");
        }
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
