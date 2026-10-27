package com.eldermoraes.seo.ssr;

import com.eldermoraes.SWObject;
import com.eldermoraes.film.Film;
import com.eldermoraes.people.People;
import com.eldermoraes.planet.Planet;
import com.eldermoraes.specie.Specie;
import com.eldermoraes.starship.Starship;
import com.eldermoraes.vehicle.Vehicle;

import java.util.List;
import java.util.function.Function;

/**
 * What the template of detail shows of each type (2.12.0, slice E2): the scalar
 * fields, in the order of the model, and the relations the API returns. Explicit
 * getters, no reflection (the native image needs none). Never url, created or
 * edited; the name or title is the heading, and a film's opening_crawl has its
 * own paragraph.
 *
 * People have no vehicles here: the data file has them but the API does not
 * expose them, and the page shows what the API shows.
 *
 * The relation getters prefix RequestBaseUrl: only RecordViews calls them, inside
 * the block that sets and restores it.
 */
public final class RecordFields {

    /** A row of the attribute table: the JSON key, the label, the value as it is in the data. */
    public record Field(String key, String label, Function<SWObject, String> value) {
    }

    /** A block of related records: the JSON key, the label, the type it points to, its URLs. */
    public record Relation(String key, String label, String type, Function<SWObject, List<String>> urls) {
    }

    private RecordFields() {
    }

    public static List<Field> attributes(String type) {
        return switch (type) {
            case "people" -> List.of(
                    people("height", "Height", People::getHeight),
                    people("mass", "Mass", People::getMass),
                    people("hair_color", "Hair color", People::getHair_color),
                    people("skin_color", "Skin color", People::getSkin_color),
                    people("eye_color", "Eye color", People::getEye_color),
                    people("birth_year", "Birth year", People::getBirth_year),
                    people("gender", "Gender", People::getGender));
            case "films" -> List.of(
                    film("episode_id", "Episode", f -> Integer.toString(f.getEpisode_id())),
                    film("director", "Director", Film::getDirector),
                    film("producer", "Producer", Film::getProducer),
                    film("release_date", "Release date", Film::getRelease_date));
            case "planets" -> List.of(
                    planet("rotation_period", "Rotation period", Planet::getRotation_period),
                    planet("orbital_period", "Orbital period", Planet::getOrbital_period),
                    planet("diameter", "Diameter", Planet::getDiameter),
                    planet("climate", "Climate", Planet::getClimate),
                    planet("gravity", "Gravity", Planet::getGravity),
                    planet("terrain", "Terrain", Planet::getTerrain),
                    planet("surface_water", "Surface water", Planet::getSurface_water),
                    planet("population", "Population", Planet::getPopulation));
            case "species" -> List.of(
                    specie("classification", "Classification", Specie::getClassification),
                    specie("designation", "Designation", Specie::getDesignation),
                    specie("average_height", "Average height", Specie::getAverage_height),
                    specie("skin_colors", "Skin colors", Specie::getSkin_colors),
                    specie("hair_colors", "Hair colors", Specie::getHair_colors),
                    specie("eye_colors", "Eye colors", Specie::getEye_colors),
                    specie("average_lifespan", "Average lifespan", Specie::getAverage_lifespan),
                    specie("language", "Language", Specie::getLanguage));
            case "starships" -> List.of(
                    starship("model", "Model", Starship::getModel),
                    starship("manufacturer", "Manufacturer", Starship::getManufacturer),
                    starship("cost_in_credits", "Cost in credits", Starship::getCost_in_credits),
                    starship("length", "Length", Starship::getLength),
                    starship("max_atmosphering_speed", "Max atmosphering speed", Starship::getMax_atmosphering_speed),
                    starship("crew", "Crew", Starship::getCrew),
                    starship("passengers", "Passengers", Starship::getPassengers),
                    starship("cargo_capacity", "Cargo capacity", Starship::getCargo_capacity),
                    starship("consumables", "Consumables", Starship::getConsumables),
                    starship("hyperdrive_rating", "Hyperdrive rating", Starship::getHyperdrive_rating),
                    starship("MGLT", "MGLT", Starship::getMGLT),
                    starship("starship_class", "Starship class", Starship::getStarship_class));
            case "vehicles" -> List.of(
                    vehicle("model", "Model", Vehicle::getModel),
                    vehicle("manufacturer", "Manufacturer", Vehicle::getManufacturer),
                    vehicle("cost_in_credits", "Cost in credits", Vehicle::getCost_in_credits),
                    vehicle("length", "Length", Vehicle::getLength),
                    vehicle("max_atmosphering_speed", "Max atmosphering speed", Vehicle::getMax_atmosphering_speed),
                    vehicle("crew", "Crew", Vehicle::getCrew),
                    vehicle("passengers", "Passengers", Vehicle::getPassengers),
                    vehicle("cargo_capacity", "Cargo capacity", Vehicle::getCargo_capacity),
                    vehicle("consumables", "Consumables", Vehicle::getConsumables),
                    vehicle("vehicle_class", "Vehicle class", Vehicle::getVehicle_class));
            default -> throw new IllegalArgumentException("unknown record type: " + type);
        };
    }

    public static List<Relation> relations(String type) {
        return switch (type) {
            case "people" -> List.of(
                    new Relation("homeworld", "Homeworld", "planets", e -> one(((People) e).getHomeworld())),
                    new Relation("films", "Films", "films", e -> many(((People) e).getFilms())),
                    new Relation("species", "Species", "species", e -> many(((People) e).getSpecies())),
                    new Relation("starships", "Starships", "starships", e -> many(((People) e).getStarships())));
            case "films" -> List.of(
                    new Relation("characters", "Characters", "people", e -> many(((Film) e).getCharacters())),
                    new Relation("planets", "Planets", "planets", e -> many(((Film) e).getPlanets())),
                    new Relation("starships", "Starships", "starships", e -> many(((Film) e).getStarships())),
                    new Relation("vehicles", "Vehicles", "vehicles", e -> many(((Film) e).getVehicles())),
                    new Relation("species", "Species", "species", e -> many(((Film) e).getSpecies())));
            case "planets" -> List.of(
                    new Relation("residents", "Residents", "people", e -> many(((Planet) e).getResidents())),
                    new Relation("films", "Films", "films", e -> many(((Planet) e).getFilms())));
            case "species" -> List.of(
                    new Relation("homeworld", "Homeworld", "planets", e -> one(((Specie) e).getHomeworld())),
                    new Relation("people", "People", "people", e -> many(((Specie) e).getPeople())),
                    new Relation("films", "Films", "films", e -> many(((Specie) e).getFilms())));
            case "starships" -> List.of(
                    new Relation("pilots", "Pilots", "people", e -> many(((Starship) e).getPilots())),
                    new Relation("films", "Films", "films", e -> many(((Starship) e).getFilms())));
            case "vehicles" -> List.of(
                    new Relation("pilots", "Pilots", "people", e -> many(((Vehicle) e).getPilots())),
                    new Relation("films", "Films", "films", e -> many(((Vehicle) e).getFilms())));
            default -> throw new IllegalArgumentException("unknown record type: " + type);
        };
    }

    /** A single relation (homeworld): empty when the data has none ("" from the getter, or null). */
    private static List<String> one(String url) {
        return url == null || url.isEmpty() ? List.of() : List.of(url);
    }

    private static List<String> many(List<String> urls) {
        return urls == null ? List.of() : urls;
    }

    private static Field people(String key, String label, Function<People, String> getter) {
        return new Field(key, label, e -> getter.apply((People) e));
    }

    private static Field film(String key, String label, Function<Film, String> getter) {
        return new Field(key, label, e -> getter.apply((Film) e));
    }

    private static Field planet(String key, String label, Function<Planet, String> getter) {
        return new Field(key, label, e -> getter.apply((Planet) e));
    }

    private static Field specie(String key, String label, Function<Specie, String> getter) {
        return new Field(key, label, e -> getter.apply((Specie) e));
    }

    private static Field starship(String key, String label, Function<Starship, String> getter) {
        return new Field(key, label, e -> getter.apply((Starship) e));
    }

    private static Field vehicle(String key, String label, Function<Vehicle, String> getter) {
        return new Field(key, label, e -> getter.apply((Vehicle) e));
    }
}
