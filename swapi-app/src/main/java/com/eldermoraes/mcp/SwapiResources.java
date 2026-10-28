package com.eldermoraes.mcp;

import com.eldermoraes.RequestBaseUrl;
import com.eldermoraes.mcp.SwapiTools.SwResource;
import com.eldermoraes.seo.KnownEntities;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;

import java.util.regex.Pattern;

/**
 * MCP resources (2.13.0, slice F1): the same records as sw_get, read by URI.
 *
 * Concrete resources, registered at startup from KnownEntities (the "@" picker
 * of Claude Code and the attachments of Claude Desktop show only what
 * resources/list returns): six indexes, swapi://people ... swapi://vehicles,
 * and one per record, swapi://people/1 and so on, whose text is the sw_get
 * text byte for byte. quarkus.mcp.server.resources.page-size=300 keeps the
 * list in one page.
 *
 * Six templates, swapi://people/{id} and so on, for clients that read by
 * template: the id arrives as text and only the canonical form (digits, no
 * leading zero) is a record; anything else returns null, which the extension
 * answers as "not found" (-32602 stateless, -32002 in a session).
 *
 * Registering resources makes the extension advertise subscribe and
 * listChanged. Neither produces traffic: McpTransportFilter answers the listen
 * and the session subscribe itself, and the list never changes.
 *
 * Every read goes through SwapiTools.withBaseUrl: the URLs follow the request
 * host (or swapi.public-base-url), and the thread gets its previous base back.
 */
@ApplicationScoped
public class SwapiResources {

    static final String SCHEME = "swapi://";
    static final String MIME = "application/json";

    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,8}");

    @Inject SwapiTools tools;
    @Inject KnownEntities known;
    @Inject ResourceManager resources;

    void register(@Observes StartupEvent event) {
        for (SwResource resource : SwResource.values()) {
            String type = resource.type();
            String index = SCHEME + type;
            resources.newResource(type)
                    .setUri(index)
                    .setTitle("Star Wars " + type + " (index)")
                    .setDescription("Every " + type + " record of swapi.build: id, name"
                            + (resource == SwResource.FILMS ? " (the title)" : "") + ", uri and url.")
                    .setMimeType(MIME)
                    .setHandler(args -> response(index, index(resource)))
                    .register();
            for (int id : known.ids(type)) {
                String uri = SCHEME + type + "/" + id;
                String name = known.name(type, Integer.toString(id)).orElse(type + " " + id);
                resources.newResource(name + " (" + type + "/" + id + ")")
                        .setUri(uri)
                        .setDescription("The " + type + " record " + id + " of swapi.build, as sw_get returns it.")
                        .setMimeType(MIME)
                        .setHandler(args -> response(uri, record(resource, id)))
                        .register();
            }
        }
    }

    private static ResourceResponse response(String uri, String text) {
        return text == null ? null : new ResourceResponse(new TextResourceContents(uri, text, MIME));
    }

    /** The sw_get text of a record, or null when it does not exist. */
    String record(SwResource resource, int id) {
        return tools.withBaseUrl(() -> tools.recordText(resource, id));
    }

    /** [{"id":1,"name":"Luke Skywalker","uri":"swapi://people/1","url":"<base>/people/1"}, ...] */
    String index(SwResource resource) {
        String type = resource.type();
        return tools.withBaseUrl(() -> {
            String base = RequestBaseUrl.get();
            JsonArrayBuilder out = Json.createArrayBuilder();
            for (int id : known.ids(type)) {
                String key = Integer.toString(id);
                out.add(Json.createObjectBuilder()
                        .add("id", id)
                        .add("name", known.name(type, key).orElse(""))
                        .add("uri", SCHEME + type + "/" + id)
                        .add("url", base + "/" + type + "/" + id));
            }
            return out.build().toString();
        });
    }

    private TextResourceContents template(SwResource resource, String id) {
        if (id == null || !CANONICAL_ID.matcher(id).matches()) {
            return null;
        }
        String text = record(resource, Integer.parseInt(id));
        return text == null ? null : new TextResourceContents(SCHEME + resource.type() + "/" + id, text, MIME);
    }

    @ResourceTemplate(name = "people_record", title = "Star Wars person by id", uriTemplate = "swapi://people/{id}",
            mimeType = MIME, description = "One person, as sw_get returns it. The id is the last segment of its url.")
    TextResourceContents peopleRecord(@ResourceTemplateArg(name = "id") String id) {
        return template(SwResource.PEOPLE, id);
    }

    @ResourceTemplate(name = "films_record", title = "Star Wars film by id", uriTemplate = "swapi://films/{id}",
            mimeType = MIME, description = "One film, as sw_get returns it. The id is a record id, not an episode "
            + "number: 1 is A New Hope.")
    TextResourceContents filmsRecord(@ResourceTemplateArg(name = "id") String id) {
        return template(SwResource.FILMS, id);
    }

    @ResourceTemplate(name = "planets_record", title = "Star Wars planet by id", uriTemplate = "swapi://planets/{id}",
            mimeType = MIME, description = "One planet, as sw_get returns it.")
    TextResourceContents planetsRecord(@ResourceTemplateArg(name = "id") String id) {
        return template(SwResource.PLANETS, id);
    }

    @ResourceTemplate(name = "species_record", title = "Star Wars species by id", uriTemplate = "swapi://species/{id}",
            mimeType = MIME, description = "One species, as sw_get returns it.")
    TextResourceContents speciesRecord(@ResourceTemplateArg(name = "id") String id) {
        return template(SwResource.SPECIES, id);
    }

    @ResourceTemplate(name = "starships_record", title = "Star Wars starship by id",
            uriTemplate = "swapi://starships/{id}", mimeType = MIME,
            description = "One starship, as sw_get returns it.")
    TextResourceContents starshipsRecord(@ResourceTemplateArg(name = "id") String id) {
        return template(SwResource.STARSHIPS, id);
    }

    @ResourceTemplate(name = "vehicles_record", title = "Star Wars vehicle by id",
            uriTemplate = "swapi://vehicles/{id}", mimeType = MIME,
            description = "One vehicle, as sw_get returns it.")
    TextResourceContents vehiclesRecord(@ResourceTemplateArg(name = "id") String id) {
        return template(SwResource.VEHICLES, id);
    }
}
