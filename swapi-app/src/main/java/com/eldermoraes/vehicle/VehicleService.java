package com.eldermoraes.vehicle;

import com.eldermoraes.SWService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;

import java.io.InputStream;
import java.lang.reflect.Type;
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
    public void setBaseUrl(String baseUrl) {
        if (vehicleList != null) {
            String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            vehicleList.forEach(vehicle -> vehicle.setBaseUrl(cleanUrl));
        }
    }

    @Override
    public void loadJsonData() {
        JsonbConfig config = new JsonbConfig().withFormatting(true);
        try (Jsonb jsonb = JsonbBuilder.create(config);
             InputStream is = Thread.currentThread().getContextClassLoader()
                     .getResourceAsStream("vehicles.json")) {

            if (is == null) {
                System.err.println("Could not find vehicles.json in resources");
                vehicleList = new ArrayList<>();
                return;
            }

            Type listType = new ArrayList<Vehicle>(){}.getClass().getGenericSuperclass();
            vehicleList = jsonb.fromJson(is, listType);

        } catch (Exception e) {
            System.err.println("Error loading films: " + e.getMessage());
            vehicleList = new ArrayList<>();
        }
    }

    public List<Vehicle> getAllVehicles() {
        return vehicleList;
    }

    public Vehicle getVehicleByName(String name) {
        return vehicleList.stream()
                .filter(vehicle -> vehicle.getName().toLowerCase().contains(name.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    public Vehicle getVehicleById(int id) {
        return vehicleList.get(id - 1);
    }

    public Vehicle getRandomVehicle() {
        if (vehicleList == null || vehicleList.isEmpty()) {
            return null;
        }
        return vehicleList.get(ThreadLocalRandom.current().nextInt(vehicleList.size()));
    }
}
