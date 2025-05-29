package com.eldermoraes.specie;

import com.eldermoraes.SWObject;

import java.util.List;

public class Specie extends SWObject {

    private String name;
    private String classification;
    private String designation;
    private String average_height;
    private String skin_colors;
    private String hair_colors;
    private String eye_colors;
    private String average_lifespan;
    private String homeworld;
    private String language;
    private List<String> people;
    private List<String> films;
    private String created;
    private String edited;
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
