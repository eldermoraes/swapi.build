package com.eldermoraes.seo.ssr;

import io.quarkus.runtime.Startup;
import io.quarkus.smallrye.openapi.runtime.OpenApiDocumentService;
import io.smallrye.openapi.runtime.io.Format;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The OpenAPI document of the app, read once at startup and turned into the
 * DocsView of /docs (slice E, 2.11.0).
 *
 * The source is OpenApiDocumentService, an internal Quarkus API: for an unknown
 * or unprepared name it returns a static empty document instead of failing, so
 * an empty {@code paths} counts as a failure. A failure never stops the app: it
 * logs an ERROR and /docs leaves as the shell without content (the content
 * probe fails, which is a rollback), with /api, /mcp and the other pages up.
 */
@Startup
@Singleton
public class OpenApiDocs {

    private static final Logger LOG = Logger.getLogger(OpenApiDocs.class);
    private static final String DOCUMENT = "<default>";
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    /**
     * The order TypeScript localeCompare gave the paths in 2.10.0, without a
     * Collator (locale data in the native image): punctuation before digits,
     * digits before letters, letters without case, then code order. So
     * /api/people/{id} comes before /api/people/random, which String.compareTo
     * would invert ({ is 123, r is 114).
     */
    public static final Comparator<String> PATH_ORDER = (a, b) -> {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            int rank = Integer.compare(rank(x), rank(y));
            if (rank != 0) {
                return rank;
            }
            int letter = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
            if (letter != 0) {
                return letter;
            }
        }
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    };

    private final Optional<JsonObject> document;
    private final Optional<DocsView> view;

    @Inject
    OpenApiDocs(OpenApiDocumentService service) {
        this(() -> service.getDocument(DOCUMENT, Format.JSON));
    }

    private OpenApiDocs(Supplier<byte[]> source) {
        Optional<JsonObject> document = Optional.empty();
        Optional<DocsView> view = Optional.empty();
        try {
            JsonObject spec;
            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(source.get()))) {
                spec = reader.readObject();
            }
            view = Optional.of(parse(spec));
            document = Optional.of(spec);
        } catch (RuntimeException e) {
            LOG.errorf(e, "OpenAPI document unavailable: /docs (page docs) is served without content");
        }
        this.document = document;
        this.view = view;
    }

    /** For tests: the same reading over any source of bytes. */
    static OpenApiDocs from(Supplier<byte[]> source) {
        return new OpenApiDocs(source);
    }

    public Optional<JsonObject> document() {
        return document;
    }

    public Optional<DocsView> view() {
        return view;
    }

    static DocsView parse(JsonObject spec) {
        JsonObject paths = spec.getJsonObject("paths");
        if (paths == null || paths.isEmpty()) {
            throw new IllegalStateException("the OpenAPI document has no paths");
        }
        JsonObject info = spec.getJsonObject("info");
        List<DocsView.Tag> tags = new ArrayList<>();
        for (String tag : orderedTags(spec, paths)) {
            List<Map.Entry<String, JsonObject>> operations = new ArrayList<>();
            for (Map.Entry<String, JsonValue> path : paths.entrySet()) {
                JsonObject get = path.getValue().asJsonObject().getJsonObject("get");
                if (get != null && strings(get.getJsonArray("tags")).contains(tag)) {
                    operations.add(Map.entry(path.getKey(), get));
                }
            }
            operations.sort((x, y) -> PATH_ORDER.compare(x.getKey(), y.getKey()));
            Optional<String> schemaName = schemaName(operations);
            JsonObject schema = schemaName.map(name -> object(object(spec, "components"), "schemas"))
                    .map(schemas -> schemas.getJsonObject(schemaName.get()))
                    .orElse(null);
            tags.add(new DocsView.Tag(tag, tagDescription(spec, tag),
                    operations.stream().map(e -> operation(e.getKey(), e.getValue())).toList(),
                    schema != null, schemaName.orElse(""), schema == null ? List.of() : fields(schema)));
        }
        return new DocsView(text(spec, "openapi", ""), text(info, "version", ""), text(info, "description", ""), tags);
    }

    // Root first, the others in the declared order, then any tag only used.
    private static List<String> orderedTags(JsonObject spec, JsonObject paths) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonValue item : paths.values()) {
            JsonObject get = item.asJsonObject().getJsonObject("get");
            if (get != null) {
                seen.addAll(strings(get.getJsonArray("tags")));
            }
        }
        List<String> ordered = new ArrayList<>();
        for (JsonValue tag : array(spec, "tags")) {
            String name = text(tag.asJsonObject(), "name", "");
            if (seen.contains(name) && !ordered.contains(name)) {
                ordered.add(name);
            }
        }
        for (String name : seen) {
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }
        if (ordered.remove("Root")) {
            ordered.add(0, "Root");
        }
        return ordered;
    }

    private static String tagDescription(JsonObject spec, String tag) {
        for (JsonValue t : array(spec, "tags")) {
            if (tag.equals(text(t.asJsonObject(), "name", ""))) {
                return text(t.asJsonObject(), "description", "");
            }
        }
        return "";
    }

    // The first $ref of the responses: directly on the by-id operations, inside items on the lists.
    private static Optional<String> schemaName(List<Map.Entry<String, JsonObject>> operations) {
        for (Map.Entry<String, JsonObject> op : operations) {
            for (JsonValue response : object(op.getValue(), "responses").values()) {
                for (JsonValue media : object(response.asJsonObject(), "content").values()) {
                    JsonObject schema = media.asJsonObject().getJsonObject("schema");
                    if (schema == null) {
                        continue;
                    }
                    String ref = text(schema, "$ref", null);
                    if (ref == null && schema.getJsonObject("items") != null) {
                        ref = text(schema.getJsonObject("items"), "$ref", null);
                    }
                    if (ref != null && ref.startsWith(SCHEMA_REF_PREFIX)) {
                        return Optional.of(ref.substring(SCHEMA_REF_PREFIX.length()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static DocsView.Operation operation(String path, JsonObject op) {
        JsonObject search = null;
        JsonObject id = null;
        for (JsonValue p : array(op, "parameters")) {
            JsonObject param = p.asJsonObject();
            String in = text(param, "in", "");
            String name = text(param, "name", "");
            if (search == null && in.equals("query") && name.equals("search")) {
                search = param;
            }
            if (id == null && in.equals("path") && name.equals("id")) {
                id = param;
            }
        }
        String displayPath = search == null ? path : path + "?search=" + example(search, "value");
        List<String> parts = new ArrayList<>();
        for (String key : List.of("summary", "description")) {
            String value = text(op, key, "");
            if (!value.isEmpty()) {
                parts.add(value);
            }
        }
        return new DocsView.Operation(path, displayPath, String.join(" — ", parts),
                object(op, "responses").containsKey("404"),
                id != null, id == null ? "" : example(id, "1"), id == null ? "" : text(id, "description", "id"),
                search != null, search == null ? "" : text(search, "description", "search"));
    }

    private static List<DocsView.Field> fields(JsonObject schema) {
        List<DocsView.Field> fields = new ArrayList<>();
        for (Map.Entry<String, JsonValue> e : object(schema, "properties").entrySet()) {
            JsonObject prop = e.getValue().asJsonObject();
            String type = "array".equals(typeText(prop.get("type")))
                    ? "array of " + Optional.ofNullable(prop.getJsonObject("items"))
                            .map(items -> typeText(items.get("type"))).filter(t -> !t.isEmpty()).orElse("string")
                    : typeText(prop.get("type"));
            fields.add(new DocsView.Field(e.getKey(), type, text(prop, "description", "")));
        }
        return fields;
    }

    // String(value) of the client: a string as it is, a list joined by commas, a number in decimal.
    private static String typeText(JsonValue value) {
        if (value == null || value == JsonValue.NULL) {
            return "";
        }
        if (value instanceof JsonString s) {
            return s.getString();
        }
        if (value instanceof JsonArray a) {
            return String.join(",", a.stream().map(OpenApiDocs::typeText).toList());
        }
        return value.toString();
    }

    private static String example(JsonObject param, String fallback) {
        JsonValue example = param.get("example");
        return example == null || example == JsonValue.NULL ? fallback : typeText(example);
    }

    private static List<String> strings(JsonArray array) {
        return array == null ? List.of() : array.stream()
                .filter(v -> v instanceof JsonString).map(v -> ((JsonString) v).getString()).toList();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonArray a = o == null ? null : o.getJsonArray(key);
        return a == null ? JsonValue.EMPTY_JSON_ARRAY : a;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonObject value = o == null ? null : o.getJsonObject(key);
        return value == null ? JsonValue.EMPTY_JSON_OBJECT : value;
    }

    private static String text(JsonObject o, String key, String fallback) {
        JsonValue value = o == null ? null : o.get(key);
        return value instanceof JsonString s ? s.getString() : fallback;
    }

    private static int rank(char c) {
        return Character.isLetter(c) ? 2 : Character.isDigit(c) ? 1 : 0;
    }
}
