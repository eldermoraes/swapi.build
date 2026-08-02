package com.eldermoraes.planet;

import com.eldermoraes.SWObject;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@RegisterForReflection
@Schema(description = "A planet in the Star Wars universe")
public class Planet extends SWObject {

    @Schema(description = "Name of this planet") private String name;
    @Schema(description = "Rotation period in standard hours, as a string") private String rotation_period;
    @Schema(description = "Orbital period in standard days, as a string") private String orbital_period;
    @Schema(description = "Diameter in kilometers, as a string") private String diameter;
    @Schema(description = "Climate(s), comma-separated") private String climate;
    @Schema(description = "Gravity, where \"1 standard\" is Earth-like, e.g. \"1 standard\", \"2.5 standard\"") private String gravity;
    @Schema(description = "Terrain type(s), comma-separated") private String terrain;
    @Schema(description = "Percentage of the surface covered by water, as a string") private String surface_water;
    @Schema(description = "Average population; \"unknown\" when not recorded") private String population;
    @Schema(description = "URLs of the people resources that live on this planet") private List<String> residents;
    @Schema(description = "URLs of the film resources this planet appeared in") private List<String> films;
    @Schema(description = "ISO 8601 timestamp of when this resource was created")
    private String created;
    @Schema(description = "ISO 8601 timestamp of when this resource was last edited")
    private String edited;
    @Schema(description = "Canonical URL of this resource, built from the request's base URL")
    private String url;

    public Planet() {
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRotation_period() {
        return rotation_period;
    }

    public void setRotation_period(String rotation_period) {
        this.rotation_period = rotation_period;
    }

    public String getOrbital_period() {
        return orbital_period;
    }

    public void setOrbital_period(String orbital_period) {
        this.orbital_period = orbital_period;
    }

    public String getDiameter() {
        return diameter;
    }

    public void setDiameter(String diameter) {
        this.diameter = diameter;
    }

    public String getClimate() {
        return climate;
    }

    public void setClimate(String climate) {
        this.climate = climate;
    }

    public String getGravity() {
        return gravity;
    }

    public void setGravity(String gravity) {
        this.gravity = gravity;
    }

    public String getTerrain() {
        return terrain;
    }

    public void setTerrain(String terrain) {
        this.terrain = terrain;
    }

    public String getSurface_water() {
        return surface_water;
    }

    public void setSurface_water(String surface_water) {
        this.surface_water = surface_water;
    }

    public String getPopulation() {
        return population;
    }

    public void setPopulation(String population) {
        this.population = population;
    }

    public List<String> getResidents() {
        if (residents != null && getBaseUrl() != null) {
            return residents.stream()
                    .map(resident -> getBaseUrl() + resident)
                    .toList();
        }
        return residents;
    }

    public void setResidents(List<String> residents) {
        this.residents = residents;
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
