package com.eldermoraes.vehicle;

import com.eldermoraes.SWObject;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@RegisterForReflection
@Schema(description = "A vehicle (transport without hyperdrive) in the Star Wars universe")
public class Vehicle extends SWObject {

    @Schema(description = "Common name of this vehicle") private String name;
    @Schema(description = "Model or official name, e.g. \"All-Terrain Attack Transport\"") private String model;
    @Schema(description = "Manufacturer(s), comma-separated") private String manufacturer;
    @Schema(description = "Cost in galactic credits, as a string") private String cost_in_credits;
    @Schema(description = "Length in meters, as a string") private String length;
    @Schema(description = "Maximum speed in atmosphere") private String max_atmosphering_speed;
    @Schema(description = "Number of personnel needed to run or pilot this vehicle") private String crew;
    @Schema(description = "Number of non-essential people this vehicle can transport") private String passengers;
    @Schema(description = "Maximum cargo capacity in kilograms, as a string") private String cargo_capacity;
    @Schema(description = "Maximum time this vehicle can provide consumables for its crew") private String consumables;
    @Schema(description = "Class of this vehicle, e.g. \"Wheeled\"") private String vehicle_class;
    @Schema(description = "URLs of the people resources that have piloted this vehicle") private List<String> pilots;
    @Schema(description = "URLs of the film resources this vehicle appeared in") private List<String> films;
    @Schema(description = "ISO 8601 timestamp of when this resource was created")
    private String created;
    @Schema(description = "ISO 8601 timestamp of when this resource was last edited")
    private String edited;
    @Schema(description = "Canonical URL of this resource, built from the request's base URL")
    private String url;

    public Vehicle() {
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getCost_in_credits() {
        return cost_in_credits;
    }

    public void setCost_in_credits(String cost_in_credits) {
        this.cost_in_credits = cost_in_credits;
    }

    public String getLength() {
        return length;
    }

    public void setLength(String length) {
        this.length = length;
    }

    public String getMax_atmosphering_speed() {
        return max_atmosphering_speed;
    }

    public void setMax_atmosphering_speed(String max_atmosphering_speed) {
        this.max_atmosphering_speed = max_atmosphering_speed;
    }

    public String getCrew() {
        return crew;
    }

    public void setCrew(String crew) {
        this.crew = crew;
    }

    public String getPassengers() {
        return passengers;
    }

    public void setPassengers(String passengers) {
        this.passengers = passengers;
    }

    public String getCargo_capacity() {
        return cargo_capacity;
    }

    public void setCargo_capacity(String cargo_capacity) {
        this.cargo_capacity = cargo_capacity;
    }

    public String getConsumables() {
        return consumables;
    }

    public void setConsumables(String consumables) {
        this.consumables = consumables;
    }

    public String getVehicle_class() {
        return vehicle_class;
    }

    public void setVehicle_class(String vehicle_class) {
        this.vehicle_class = vehicle_class;
    }

    public List<String> getPilots() {
        if (pilots != null && getBaseUrl() != null) {
            return pilots.stream()
                    .map(pilot -> getBaseUrl() + pilot)
                    .toList();
        }
        return pilots;
    }

    public void setPilots(List<String> pilots) {
        this.pilots = pilots;
    }

    public List<String> getFilms() {
        if (films != null && getBaseUrl() != null) {
            return films.stream()
                    .map(film -> getBaseUrl() + film)
                    .toList();
        }
        return films;
    }

    public void setFilms(List<String> films) {
        this.films = films;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getEdited() {
        return edited;
    }

    public void setEdited(String edited) {
        this.edited = edited;
    }

    public String getUrl() {
        return getBaseUrl() + url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
