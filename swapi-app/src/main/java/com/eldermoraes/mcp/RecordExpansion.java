package com.eldermoraes.mcp;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.SWObject;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.seo.KnownEntities;
import com.eldermoraes.seo.ssr.RecordFields;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import org.jboss.logging.Logger;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The two optional arguments of 2.13.0 (slice F1), over the text the tools
 * already produce, so that without them the text is the one of 2.12.1:
 *
 * - expand (sw_get): the text of the record without its final "}", then
 *   ,"expanded":{...}} with one key per relation asked for, in the order of
 *   RecordFields.relations. A single relation (homeworld) is the record or
 *   null; any other is an array in the order and with the length of its URLs,
 *   null where a URL points to no record. One level: the embedded records are
 *   the sw_get text of each, never expanded again.
 * - fields (sw_list, sw_search): each record keeps only the fields asked for
 *   plus url, in the order of the full record, values as they are (JSON-P,
 *   compact).
 *
 * Both validate before any read. The relation names come from RecordFields;
 * the valid fields are the keys JSON-B emits for the records of each type,
 * computed once at startup in a block that sets and restores RequestBaseUrl.
 * Every caller other than that block runs inside SwapiTools.withBaseUrl.
 */
@ApplicationScoped
public class RecordExpansion {

    private static final Logger LOG = Logger.getLogger(RecordExpansion.class);

    static final int MAX_EXPAND = 8;
    static final int MAX_FIELDS = 24;
    static final int MAX_NAME = 40;
    static final String ALL = "*";

    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,8}");
    private static final String FIELDS_BASE = "http://fields.invalid/api";

    private final PeopleService people;
    private final FilmService films;
    private final PlanetService planets;
    private final SpecieService species;
    private final StarshipService starships;
    private final VehicleService vehicles;
    private final Jsonb jsonb;

    private volatile Map<String, Set<String>> validFields;

    @Inject
    public RecordExpansion(PeopleService people, FilmService films, PlanetService planets, SpecieService species,
                           StarshipService starships, VehicleService vehicles, Jsonb jsonb) {
        this.people = people;
        this.films = films;
        this.planets = planets;
        this.species = species;
        this.starships = starships;
        this.vehicles = vehicles;
        this.jsonb = jsonb;
    }

    void warm(@Observes StartupEvent event) {
        validFields = buildValidFields();
    }

    // ---- expand ----

    /**
     * The relations of a type that expand asks for, in the order of
     * RecordFields; empty for null or []. Repeated names count once; "*" alone
     * means every relation. A tool error for anything else, before any read.
     */
    public List<RecordFields.Relation> relations(String type, List<String> expand) {
        if (expand == null || expand.isEmpty()) {
            return List.of();
        }
        if (expand.size() > MAX_EXPAND) {
            throw new ToolCallException("expand takes at most " + MAX_EXPAND + " relation names, got "
                    + expand.size() + ".");
        }
        List<RecordFields.Relation> all = RecordFields.relations(type);
        Set<String> asked = new LinkedHashSet<>();
        for (String name : expand) {
            if (name == null) {
                throw new ToolCallException("expand takes relation names (strings), not null.");
            }
            if (name.length() > MAX_NAME) {
                throw new ToolCallException("A relation name in expand has at most " + MAX_NAME + " characters.");
            }
            asked.add(name);
        }
        if (asked.contains(ALL)) {
            if (asked.size() > 1) {
                throw new ToolCallException("expand: use * alone, or name the relations.");
            }
            return all;
        }
        for (String name : asked) {
            if (all.stream().noneMatch(r -> r.key().equals(name))) {
                throw new ToolCallException("Unknown relation '" + name + "' for " + type + ". Valid: "
                        + String.join(", ", all.stream().map(RecordFields.Relation::key).toList()) + ", or *.");
            }
        }
        return all.stream().filter(r -> asked.contains(r.key())).toList();
    }

    /**
     * The text of the record with ,"expanded":{...} before its final "}".
     * text is the JSON-B text of entity, as sw_get returns it.
     */
    public String expand(String type, SWObject entity, String text, List<RecordFields.Relation> relations) {
        if (relations.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() * 4);
        out.append(text, 0, text.lastIndexOf('}')).append(",\"expanded\":{");
        boolean first = true;
        for (RecordFields.Relation relation : relations) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(relation.key()).append("\":");
            List<String> urls = relation.urls().apply(entity);
            if (relation.single()) {
                out.append(urls.isEmpty() ? "null" : recordText(type, relation, urls.getFirst()));
            } else {
                out.append('[');
                for (int i = 0; i < urls.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(recordText(type, relation, urls.get(i)));
                }
                out.append(']');
            }
        }
        return out.append("}}").toString();
    }

    private String recordText(String type, RecordFields.Relation relation, String url) {
        SWObject target = byUrl(relation.type(), url);
        if (target == null) {
            LOG.warnf("expand: %s.%s points to %s, which is not a record", type, relation.key(), url);
            return "null";
        }
        return jsonb.toJson(target);
    }

    /** The record a relation URL points to: the suffix /type/id only, never the host. */
    private SWObject byUrl(String type, String url) {
        String prefix = "/" + type + "/";
        int at = url == null ? -1 : url.lastIndexOf(prefix);
        if (at < 0) {
            return null;
        }
        String id = url.substring(at + prefix.length());
        if (!CANONICAL_ID.matcher(id).matches()) {
            return null;
        }
        return entity(type, Integer.parseInt(id));
    }

    SWObject entity(String type, int id) {
        return switch (type) {
            case "people" -> people.getPeopleById(id);
            case "films" -> films.getFilmById(id);
            case "planets" -> planets.getPlanetById(id);
            case "species" -> species.getSpecieById(id);
            case "starships" -> starships.getStarshipById(id);
            case "vehicles" -> vehicles.getVehicleById(id);
            default -> null;
        };
    }

    // ---- fields ----

    /**
     * The fields of a type that fields asks for; null for null or [] (no
     * projection). Repeated names count once. A tool error for anything else,
     * before any read.
     */
    public Set<String> fields(String type, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        if (fields.size() > MAX_FIELDS) {
            throw new ToolCallException("fields takes at most " + MAX_FIELDS + " field names, got "
                    + fields.size() + ".");
        }
        Set<String> valid = validFields(type);
        Set<String> asked = new LinkedHashSet<>();
        for (String name : fields) {
            if (name == null) {
                throw new ToolCallException("fields takes field names (strings), not null.");
            }
            if (name.length() > MAX_NAME) {
                throw new ToolCallException("A field name in fields has at most " + MAX_NAME + " characters.");
            }
            if (!valid.contains(name)) {
                throw new ToolCallException("Unknown field '" + name + "' for " + type + ". Valid: "
                        + String.join(", ", valid) + " (url is always included).");
            }
            asked.add(name);
        }
        asked.add("url");
        return asked;
    }

    /** The array text with each record cut to the fields (plus url), in the record's own key order. */
    public String project(String arrayText, Set<String> fields) {
        if (fields == null) {
            return arrayText;
        }
        JsonArray records;
        try (JsonReader reader = Json.createReader(new StringReader(arrayText))) {
            records = reader.readArray();
        }
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (JsonValue value : records) {
            JsonObject record = value.asJsonObject();
            JsonObjectBuilder cut = Json.createObjectBuilder();
            for (Map.Entry<String, JsonValue> field : record.entrySet()) {
                if (fields.contains(field.getKey())) {
                    cut.add(field.getKey(), field.getValue());
                }
            }
            out.add(cut);
        }
        return out.build().toString();
    }

    /** The keys JSON-B emits for the records of a type, in the order of the records. */
    public Set<String> validFields(String type) {
        Map<String, Set<String>> all = validFields;
        if (all == null) {
            all = buildValidFields();
            validFields = all;
        }
        Set<String> valid = all.get(type);
        if (valid == null) {
            throw new IllegalArgumentException("unknown record type: " + type);
        }
        return valid;
    }

    private Map<String, Set<String>> buildValidFields() {
        Map<String, Set<String>> byType = new HashMap<>();
        String previous = RequestBaseUrl.get();
        RequestBaseUrl.set(FIELDS_BASE);
        try {
            for (String type : KnownEntities.TYPES) {
                Set<String> keys = new LinkedHashSet<>();
                List<? extends SWObject> records = all(type);
                for (SWObject record : records == null ? List.<SWObject>of() : records) {
                    try (JsonReader reader = Json.createReader(new StringReader(jsonb.toJson(record)))) {
                        keys.addAll(reader.readObject().keySet());
                    }
                }
                byType.put(type, Collections.unmodifiableSet(keys));
            }
        } finally {
            if (previous == null) {
                RequestBaseUrl.clear();
            } else {
                RequestBaseUrl.set(previous);
            }
        }
        return Map.copyOf(byType);
    }

    private List<? extends SWObject> all(String type) {
        return switch (type) {
            case "people" -> people.getAllPeople();
            case "films" -> films.getAllFilms();
            case "planets" -> planets.getAllPlanets();
            case "species" -> species.getAllSpecies();
            case "starships" -> starships.getAllStarships();
            case "vehicles" -> vehicles.getAllVehicles();
            default -> new ArrayList<>();
        };
    }
}
