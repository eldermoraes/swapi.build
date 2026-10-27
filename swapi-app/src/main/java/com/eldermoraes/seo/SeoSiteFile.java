package com.eldermoraes.seo;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one source of page metadata (2.10.0): {@code seo/site.json}, read here by
 * the server and by webui/src/site.ts in the build. Titles, descriptions and
 * which record addresses are indexable live there and nowhere else.
 *
 * Read with JSON-P (no reflection, so nothing to register for the native image)
 * and validated on load: a missing or invalid file fails startup with the file
 * and the key in the message, instead of serving pages without a title.
 *
 * Template rule (the same in site.ts): {@code {key}} with key in [a-z_]+ is
 * replaced by the value of that field; {@code {id}} is the id of the address.
 * Only the keys of {@code detail.fields} (and id) may appear. No conditionals, no
 * nesting, no escaping in the render (escaping is at the output: html() on the
 * server, DOM text on the client).
 */
public final class SeoSiteFile {

    public static final String RESOURCE = "seo/site.json";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    public record Text(String title, String description) {
    }

    public record Page(String path, String page, String title, String description, String changefreq, String priority) {
    }

    public record Listing(String title, String description, String changefreq, String priority) {
    }

    public record Detail(boolean indexable, List<String> fields, String title, String description,
                         String fallbackTitle, String fallbackDescription, String changefreq, String priority) {
    }

    public record ResourceType(String type, Listing list, Detail detail) {
    }

    private final String brandSuffix;
    private final Text notFound;
    private final List<Page> pages;
    private final List<ResourceType> resources;

    private SeoSiteFile(String brandSuffix, Text notFound, List<Page> pages, List<ResourceType> resources) {
        this.brandSuffix = brandSuffix;
        this.notFound = notFound;
        this.pages = List.copyOf(pages);
        this.resources = List.copyOf(resources);
    }

    public String brandSuffix() {
        return brandSuffix;
    }

    public Text notFound() {
        return notFound;
    }

    public List<Page> pages() {
        return pages;
    }

    public List<ResourceType> resources() {
        return resources;
    }

    public Optional<ResourceType> resource(String type) {
        return resources.stream().filter(resource -> resource.type().equals(type)).findFirst();
    }

    public static SeoSiteFile load() {
        return load(RESOURCE);
    }

    /** Reads a classpath resource; throws IllegalStateException naming it when it is missing or invalid. */
    public static SeoSiteFile load(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SeoSiteFile.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing from the classpath"
                        + " (for the native binary: quarkus.native.resources.includes)");
            }
            try (JsonReader reader = Json.createReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return parse(reader.readObject(), resource);
            }
        } catch (IOException | JsonException | ClassCastException e) {
            throw new IllegalStateException(resource + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** The keys a template uses, {id} included. */
    public static Set<String> placeholders(String template) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /**
     * The template with every {key} replaced by its value. Empty when a key has
     * no value or a blank one: the caller falls back, so a record with an empty
     * field never gets "null" in its title. Text that is not a key stays as it is.
     */
    public static Optional<String> render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }

    private static SeoSiteFile parse(JsonObject root, String source) {
        keys(root, source, "", Set.of("brandSuffix", "notFound", "pages", "resources"));
        String brand = string(root, "brandSuffix", source, "");
        JsonObject nf = object(root, "notFound", source, "");
        keys(nf, source, "notFound", Set.of("title", "description"));
        Text notFound = new Text(title(nf, brand, source, "notFound"), string(nf, "description", source, "notFound"));

        List<Page> pages = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        JsonArray pageArray = array(root, "pages", source, "");
        for (int i = 0; i < pageArray.size(); i++) {
            String at = "pages[" + i + "]";
            JsonObject p = pageArray.getJsonObject(i);
            keys(p, source, at, Set.of("path", "page", "title", "description", "changefreq", "priority"));
            String path = string(p, "path", source, at);
            if (!path.startsWith("/") || (path.length() > 1 && path.endsWith("/")) || !paths.add(path)) {
                throw invalid(source, at + ".path", "\"" + path + "\" must start with / , have no trailing slash and be unique");
            }
            pages.add(new Page(path, string(p, "page", source, at), title(p, brand, source, at),
                    string(p, "description", source, at), string(p, "changefreq", source, at),
                    string(p, "priority", source, at)));
        }

        List<ResourceType> resources = new ArrayList<>();
        Set<String> types = new HashSet<>();
        JsonArray resourceArray = array(root, "resources", source, "");
        for (int i = 0; i < resourceArray.size(); i++) {
            String at = "resources[" + i + "]";
            JsonObject r = resourceArray.getJsonObject(i);
            keys(r, source, at, Set.of("type", "list", "detail"));
            String type = string(r, "type", source, at);
            if (!KnownEntities.TYPES.contains(type) || !types.add(type)) {
                throw invalid(source, at + ".type", "\"" + type + "\" is not one of " + KnownEntities.TYPES + " or is repeated");
            }
            JsonObject l = object(r, "list", source, at);
            String lat = at + ".list";
            keys(l, source, lat, Set.of("title", "description", "changefreq", "priority"));
            Listing list = new Listing(title(l, brand, source, lat), string(l, "description", source, lat),
                    string(l, "changefreq", source, lat), string(l, "priority", source, lat));

            JsonObject d = object(r, "detail", source, at);
            String dat = at + ".detail";
            keys(d, source, dat, Set.of("indexable", "fields", "title", "description", "fallbackTitle",
                    "fallbackDescription", "changefreq", "priority"));
            JsonValue indexable = d.get("indexable");
            if (indexable == null || (indexable != JsonValue.TRUE && indexable != JsonValue.FALSE)) {
                throw invalid(source, dat + ".indexable", "must be true or false");
            }
            List<String> fields = new ArrayList<>();
            for (JsonValue field : array(d, "fields", source, dat)) {
                if (!(field instanceof JsonString s) || !KnownEntities.supportedFields(type).contains(s.getString())) {
                    throw invalid(source, dat + ".fields", field + " is not a field of " + type
                            + " (" + KnownEntities.supportedFields(type) + ")");
                }
                fields.add(s.getString());
            }
            Set<String> allowed = new HashSet<>(fields);
            allowed.add("id");
            String title = template(d, "title", allowed, source, dat);
            String description = template(d, "description", allowed, source, dat);
            String fallbackTitle = template(d, "fallbackTitle", Set.of("id"), source, dat);
            String fallbackDescription = template(d, "fallbackDescription", Set.of("id"), source, dat);
            if (!title.endsWith(brand) || !fallbackTitle.endsWith(brand)) {
                throw invalid(source, dat + ".title", "titles must end with \"" + brand + "\"");
            }
            resources.add(new ResourceType(type, list, new Detail(indexable == JsonValue.TRUE, List.copyOf(fields),
                    title, description, fallbackTitle, fallbackDescription,
                    string(d, "changefreq", source, dat), string(d, "priority", source, dat))));
        }
        if (!types.equals(KnownEntities.TYPES)) {
            throw invalid(source, "resources", "must list exactly " + KnownEntities.TYPES + ", got " + types);
        }
        return new SeoSiteFile(brand, notFound, pages, resources);
    }

    private static String template(JsonObject o, String key, Set<String> allowed, String source, String at) {
        String value = string(o, key, source, at);
        for (String placeholder : placeholders(value)) {
            if (!allowed.contains(placeholder)) {
                throw invalid(source, at + "." + key, "{" + placeholder + "} is not one of " + allowed);
            }
        }
        return value;
    }

    private static String title(JsonObject o, String brand, String source, String at) {
        String title = string(o, "title", source, at);
        if (!title.endsWith(brand)) {
            throw invalid(source, at + ".title", "\"" + title + "\" must end with \"" + brand + "\"");
        }
        return title;
    }

    private static void keys(JsonObject o, String source, String at, Set<String> allowed) {
        for (String key : o.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(source, at.isEmpty() ? key : at + "." + key, "unknown key");
            }
        }
    }

    private static String string(JsonObject o, String key, String source, String at) {
        JsonValue value = o.get(key);
        if (!(value instanceof JsonString s) || s.getString().isBlank()) {
            throw invalid(source, at.isEmpty() ? key : at + "." + key, "missing or not a non-empty string");
        }
        return s.getString();
    }

    private static JsonObject object(JsonObject o, String key, String source, String at) {
        JsonValue value = o.get(key);
        if (!(value instanceof JsonObject object)) {
            throw invalid(source, at.isEmpty() ? key : at + "." + key, "missing or not an object");
        }
        return object;
    }

    private static JsonArray array(JsonObject o, String key, String source, String at) {
        JsonValue value = o.get(key);
        if (!(value instanceof JsonArray array)) {
            throw invalid(source, at.isEmpty() ? key : at + "." + key, "missing or not an array");
        }
        return array;
    }

    private static IllegalStateException invalid(String source, String key, String why) {
        return new IllegalStateException(source + ": " + key + ": " + why);
    }
}
