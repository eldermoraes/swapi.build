package com.eldermoraes.people;

import com.eldermoraes.SWObject;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@RegisterForReflection
@Schema(description = "A person within the Star Wars universe")
public class People extends SWObject {

    @Schema(description = "Name of this person") private String name;
    @Schema(description = "Height in centimeters, as a string; \"unknown\" when not recorded") private String height;
    @Schema(description = "Mass in kilograms, as a string; \"unknown\" when not recorded") private String mass;
    @Schema(description = "Hair color; \"n/a\" when the person has no hair") private String hair_color;
    @Schema(description = "Skin color") private String skin_color;
    @Schema(description = "Eye color") private String eye_color;
    @Schema(description = "Birth year, relative to the Battle of Yavin (BBY/ABY), e.g. \"19BBY\"") private String birth_year;
    @Schema(description = "Gender; \"n/a\" for droids") private String gender;
    @Schema(description = "URL of the planet resource this person was born on") private String homeworld;
    @Schema(description = "URLs of the film resources this person appeared in") private List<String> films;
    @Schema(description = "URLs of the species resources this person belongs to") private List<String> species;
    @Schema(description = "URLs of the vehicle resources this person has piloted") private List<String> vehicles;
    @Schema(description = "URLs of the starship resources this person has piloted") private List<String> starships;
    @Schema(description = "ISO 8601 timestamp of when this resource was created")
    private String created;
    @Schema(description = "ISO 8601 timestamp of when this resource was last edited")
    private String edited;
    @Schema(description = "Canonical URL of this resource, built from the request's base URL")
    private String url;

    public People() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHeight() {
        return height;
    }

    public void setHeight(String height) {
        this.height = height;
    }

    public String getMass() {
        return mass;
    }

    public void setMass(String mass) {
        this.mass = mass;
    }

    public String getHair_color() {
        return hair_color;
    }

    public void setHair_color(String hair_color) {
        this.hair_color = hair_color;
    }

    public String getSkin_color() {
        return skin_color;
    }

    public void setSkin_color(String skin_color) {
        this.skin_color = skin_color;
    }

    public String getEye_color() {
        return eye_color;
    }

    public void setEye_color(String eye_color) {
        this.eye_color = eye_color;
    }

    public String getBirth_year() {
        return birth_year;
    }

    public void setBirth_year(String birth_year) {
        this.birth_year = birth_year;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getHomeworld() {
        return absoluteOrEmpty(homeworld);
    }

    public void setHomeworld(String homeworld) {
        this.homeworld = homeworld;
    }

    public List<String> getFilms() {
        return absolute(films);
    }

    public void setFilms(List<String> films) {
        this.films = films;
    }

    public List<String> getSpecies() {
        return absolute(species);
    }

    public void setSpecies(List<String> species) {
        this.species = species;
    }

    public List<String> getVehicles() {
        return absolute(vehicles);
    }

    public void setVehicles(List<String> vehicles) {
        this.vehicles = vehicles;
    }

    public List<String> getStarships() {
        return absolute(starships);
    }

    public void setStarships(List<String> starships) {
        this.starships = starships;
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
        return absolute(url);
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
