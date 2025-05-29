package com.eldermoraes.film;

import java.util.List;
import com.eldermoraes.SWObject;

public class Film extends SWObject {

    private String title;
    private int episode_id;
    private String opening_crawl;
    private String director;
    private String producer;
    private String release_date;
    private List<String> characters;
    private List<String> planets;
    private List<String> starships;
    private List<String> vehicles;
    private List<String> species;
    private String created;
    private String edited;
    private String url;

    public Film() {

    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getEpisode_id() {
        return episode_id;
    }

    public void setEpisode_id(int episode_id) {
        this.episode_id = episode_id;
    }

    public String getOpening_crawl() {
        return opening_crawl;
    }

    public void setOpening_crawl(String opening_crawl) {
        this.opening_crawl = opening_crawl;
    }

    public String getDirector() {
        return director;
    }

    public void setDirector(String director) {
        this.director = director;
    }

    public String getProducer() {
        return producer;
    }

    public void setProducer(String producer) {
        this.producer = producer;
    }

    public String getRelease_date() {
        return release_date;
    }

    public void setRelease_date(String release_date) {
        this.release_date = release_date;
    }

    public List<String> getCharacters() {
        if (characters != null && getBaseUrl() != null) {
            return characters.stream()
                .map(character -> getBaseUrl() + character)
                .toList();
        }
        return characters;
    }

    public void setCharacters(List<String> characters) {
        this.characters = characters;
    }

    public List<String> getPlanets() {
        if (planets != null && getBaseUrl() != null) {
            return planets.stream()
                .map(planet -> getBaseUrl() + planet)
                .toList();
        }
        return planets;
    }

    public void setPlanets(List<String> planets) {
        this.planets = planets;
    }

    public List<String> getStarships() {
        if (starships != null && getBaseUrl() != null) {
            return starships.stream()
                .map(starship -> getBaseUrl() + starship)
                .toList();
        }
        return starships;
    }

    public void setStarships(List<String> starships) {
        this.starships = starships;
    }

    public List<String> getVehicles() {
        if (vehicles != null && getBaseUrl() != null) {
            return vehicles.stream()
                .map(vehicle -> getBaseUrl() + vehicle)
                .toList();
        }
        return vehicles;
    }

    public void setVehicles(List<String> vehicles) {
        this.vehicles = vehicles;
    }

    public List<String> getSpecies() {
        if (species != null && getBaseUrl() != null) {
            return species.stream()
                .map(specie -> getBaseUrl() + specie)
                .toList();
        }
        return species;
    }

    public void setSpecies(List<String> species) {
        this.species = species;
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
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
