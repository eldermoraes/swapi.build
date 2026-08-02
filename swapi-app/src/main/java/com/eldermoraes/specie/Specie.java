package com.eldermoraes.specie;

import com.eldermoraes.SWObject;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@RegisterForReflection
@Schema(description = "A species in the Star Wars universe")
public class Specie extends SWObject {

    @Schema(description = "Name of this species") private String name;
    @Schema(description = "Classification, e.g. \"mammal\", \"reptile\"") private String classification;
    @Schema(description = "Designation, e.g. \"sentient\"") private String designation;
    @Schema(description = "Average height in centimeters, as a string") private String average_height;
    @Schema(description = "Common skin colors, comma-separated; \"none\" when skinless") private String skin_colors;
    @Schema(description = "Common hair colors, comma-separated; \"none\" when hairless") private String hair_colors;
    @Schema(description = "Common eye colors, comma-separated") private String eye_colors;
    @Schema(description = "Average lifespan in standard years, as a string") private String average_lifespan;
    @Schema(description = "URL of the planet resource this species originates from") private String homeworld;
    @Schema(description = "Language commonly spoken by this species") private String language;
    @Schema(description = "URLs of the people resources that belong to this species") private List<String> people;
    @Schema(description = "URLs of the film resources this species appeared in") private List<String> films;
    @Schema(description = "ISO 8601 timestamp of when this resource was created")
    private String created;
    @Schema(description = "ISO 8601 timestamp of when this resource was last edited")
    private String edited;
    @Schema(description = "Canonical URL of this resource, built from the request's base URL")
    private String url;

    public Specie() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public String getAverage_height() {
        return average_height;
    }

    public void setAverage_height(String average_height) {
        this.average_height = average_height;
    }

    public String getSkin_colors() {
        return skin_colors;
    }

    public void setSkin_colors(String skin_colors) {
        this.skin_colors = skin_colors;
    }

    public String getHair_colors() {
        return hair_colors;
    }

    public void setHair_colors(String hair_colors) {
        this.hair_colors = hair_colors;
    }

    public String getEye_colors() {
        return eye_colors;
    }

    public void setEye_colors(String eye_colors) {
        this.eye_colors = eye_colors;
    }

    public String getAverage_lifespan() {
        return average_lifespan;
    }

    public void setAverage_lifespan(String average_lifespan) {
        this.average_lifespan = average_lifespan;
    }

    public String getHomeworld() {
        if (homeworld == null || homeworld.equals("null") || homeworld.isEmpty()) {
            return "";
        } else{
            return getBaseUrl() + homeworld;
        }
    }

    public void setHomeworld(String homeworld) {
        this.homeworld = homeworld;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public List<String> getPeople() {
        if (people != null && getBaseUrl() != null) {
            return people.stream()
                    .map(person -> getBaseUrl() + person)
                    .toList();
        }
        return people;
    }

    public void setPeople(List<String> people) {
        this.people = people;
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
