package com.eldermoraes.seo.ssr;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.SWObject;
import com.eldermoraes.film.Film;
import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.seo.KnownEntities;
import com.eldermoraes.seo.SeoMetadataRegistry;
import com.eldermoraes.seo.SeoSiteFile;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.json.bind.Jsonb;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The data of the template of detail (2.12.0, slice E2) for any record of the
 * six types: no file, route or code per record.
 *
 * The entities build their URLs from RequestBaseUrl, a ThreadLocal that the
 * REST filter and the MCP tools set and never clear. This runs on the event
 * loop (SeoRoutes), where the ThreadLocal may be empty ("null/people/1") or hold
 * another request's base. So every getter (attributes, relations, the JSON-B
 * serialization, which is the one the REST API uses) is called inside one block
 * that sets the base of this request and then restores the value that was there
 * before: nothing changes for whoever runs on the thread next. The view leaves
 * the block holding Strings only, and the Qute render happens outside it.
 */
@ApplicationScoped
public class RecordViews {

    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,8}");

    @Inject KnownEntities known;
    @Inject SeoMetadataRegistry metadata;
    @Inject Jsonb jsonb;
    @Inject PeopleService people;
    @Inject FilmService films;
    @Inject PlanetService planets;
    @Inject SpecieService species;
    @Inject StarshipService starships;
    @Inject VehicleService vehicles;

    /**
     * The view of a record that exists (KnownEntities.exists), empty otherwise.
     *
     * @param apiBase  the API base of this request (PublicSiteUrlResolver.apiBase), for every URL of the record
     * @param siteRoot the site root of this request, for the MCP endpoint
     */
    public Optional<RecordView> build(String type, String id, String apiBase, String siteRoot) {
        if (!known.exists(type, id)) {
            return Optional.empty();
        }
        SWObject entity = entity(type, Integer.parseInt(id));
        String typeLabel = metadata.site().resource(type).map(SeoSiteFile.ResourceType::label).orElse(type);
        String name = known.name(type, id).orElse("");

        List<RecordView.Attribute> attributes = new ArrayList<>();
        List<RecordView.Related> related = new ArrayList<>();
        String json;
        String previous = RequestBaseUrl.get();
        RequestBaseUrl.set(apiBase);
        try {
            for (RecordFields.Field field : RecordFields.attributes(type)) {
                String value = field.value().apply(entity);
                attributes.add(new RecordView.Attribute(field.label(), value == null ? "" : value));
            }
            for (RecordFields.Relation relation : RecordFields.relations(type)) {
                List<RecordView.Link> links = relation.urls().apply(entity).stream()
                        .map(url -> link(relation.type(), url))
                        .toList();
                if (!links.isEmpty()) {
                    related.add(new RecordView.Related(relation.label(), links));
                }
            }
            try (JsonReader reader = Json.createReader(new StringReader(jsonb.toJson(entity)))) {
                json = JsonText.pretty(reader.readValue());
            }
        } finally {
            if (previous == null) {
                RequestBaseUrl.clear();
            } else {
                RequestBaseUrl.set(previous);
            }
        }

        String crawl = entity instanceof Film film && film.getOpening_crawl() != null ? film.getOpening_crawl() : "";
        String url = apiBase + "/" + type + "/" + id;
        return Optional.of(new RecordView(type, id, typeLabel, name, List.copyOf(attributes), crawl,
                List.copyOf(related), "/api/" + type + "/" + id,
                "curl " + url,
                "fetch('" + url + "').then((r) => r.json())",
                "requests.get('" + url + "').json()",
                "claude mcp add --transport http swapi-build " + siteRoot + "/mcp",
                "{\"resource\":\"" + type.toUpperCase(java.util.Locale.ROOT) + "\",\"id\":" + id + "}",
                json));
    }

    /**
     * A related record: its name and its page when the URL ends in
     * {@code /<type>/<canonical id>} of a record that exists; otherwise the URL
     * itself, unlinked (none in today's data, the rule is tested).
     */
    RecordView.Link link(String type, String url) {
        Matcher m = Pattern.compile("/" + Pattern.quote(type) + "/([^/]+)$").matcher(url);
        if (m.find() && CANONICAL_ID.matcher(m.group(1)).matches()) {
            Optional<String> name = known.name(type, m.group(1));
            if (name.isPresent()) {
                return new RecordView.Link(name.get(), "/resource/" + type + "/" + m.group(1), true);
            }
        }
        return new RecordView.Link(url, "", false);
    }

    private SWObject entity(String type, int id) {
        return switch (type) {
            case "people" -> people.getPeopleById(id);
            case "films" -> films.getFilmById(id);
            case "planets" -> planets.getPlanetById(id);
            case "species" -> species.getSpecieById(id);
            case "starships" -> starships.getStarshipById(id);
            case "vehicles" -> vehicles.getVehicleById(id);
            default -> throw new IllegalArgumentException("unknown record type: " + type);
        };
    }
}
