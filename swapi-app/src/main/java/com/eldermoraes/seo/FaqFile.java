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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of the /faq page (2.15.0): {@code seo/faq.json}, read with JSON-P
 * like seo/site.json (SeoSiteFile) and validated on load, so a broken file
 * stops startup with the file and the key in the message.
 *
 * No HTML in the file: answers are paragraphs of plain text, links are fields.
 * A count that comes from the data is written as a marker, {@code {count:people}},
 * and filled from KnownEntities when the page is drawn ({@link #fill}).
 *
 * Every cell of the comparison is a dated fact: a value, an https source and
 * the day it was checked. How old a fact may be is a rule of the tests
 * (FaqFileTest), not of startup: a stale comparison stops a release, never the
 * running site.
 */
public final class FaqFile {

    public static final String RESOURCE = "seo/faq.json";

    /** The age, in days, past which a fact of the comparison must be checked again. */
    public static final int MAX_AGE_DAYS = 180;

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern MARKER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern COUNT = Pattern.compile("count:([a-z]+)");

    public record Link(String text, String path) {
    }

    public record Question(String id, String question, List<String> answer, List<Link> links) {
    }

    public record Cell(String value, String source, LocalDate checkedOn) {
    }

    public record Row(String label, List<Cell> cells) {
    }

    public record Comparison(List<String> columns, List<Row> rows) {
    }

    private final LocalDate checkedOn;
    private final List<Question> questions;
    private final Comparison comparison;

    private FaqFile(LocalDate checkedOn, List<Question> questions, Comparison comparison) {
        this.checkedOn = checkedOn;
        this.questions = List.copyOf(questions);
        this.comparison = comparison;
    }

    /** The day the comparison was last checked, shown above the table. */
    public LocalDate checkedOn() {
        return checkedOn;
    }

    public List<Question> questions() {
        return questions;
    }

    public Comparison comparison() {
        return comparison;
    }

    /** Every date of the file, with where it is: the top checkedOn and one per cell. */
    public List<Dated> dates() {
        List<Dated> dates = new ArrayList<>();
        dates.add(new Dated("checkedOn", checkedOn));
        for (int r = 0; r < comparison.rows().size(); r++) {
            Row row = comparison.rows().get(r);
            for (int c = 0; c < row.cells().size(); c++) {
                dates.add(new Dated("comparison.rows[" + r + "] (" + row.label() + ") / "
                        + comparison.columns().get(c), row.cells().get(c).checkedOn()));
            }
        }
        return dates;
    }

    public record Dated(String where, LocalDate date) {
    }

    /** The dates after {@code today}: a fact cannot have been checked tomorrow. */
    public List<Dated> datesInTheFuture(LocalDate today) {
        return dates().stream().filter(d -> d.date().isAfter(today)).toList();
    }

    /** The dates more than {@link #MAX_AGE_DAYS} days before {@code today}. */
    public List<Dated> factsOlderThanTheLimit(LocalDate today) {
        return dates().stream().filter(d -> ChronoUnit.DAYS.between(d.date(), today) > MAX_AGE_DAYS).toList();
    }

    /** Every string of the file a reader sees: questions, answers, link texts, columns, labels and values. */
    public List<String> texts() {
        List<String> texts = new ArrayList<>();
        for (Question q : questions) {
            texts.add(q.question());
            texts.addAll(q.answer());
            q.links().forEach(link -> texts.add(link.text()));
        }
        texts.addAll(comparison.columns());
        for (Row row : comparison.rows()) {
            texts.add(row.label());
            row.cells().forEach(cell -> texts.add(cell.value()));
        }
        return texts;
    }

    /** The text with every {count:type} replaced by the number of records of that type. */
    public static String fill(String text, ToIntFunction<String> count) {
        Matcher m = MARKER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Matcher c = COUNT.matcher(m.group(1));
            if (!c.matches()) {
                throw new IllegalStateException(RESOURCE + ": unknown marker {" + m.group(1) + "}");
            }
            m.appendReplacement(out, Integer.toString(count.applyAsInt(c.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static FaqFile load() {
        return load(RESOURCE);
    }

    /** Reads a classpath resource; throws IllegalStateException naming it and the key when it is missing or invalid. */
    public static FaqFile load(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = FaqFile.class.getClassLoader();
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

    private static FaqFile parse(JsonObject root, String source) {
        keys(root, source, "", Set.of("checkedOn", "questions", "comparison"));
        LocalDate checkedOn = date(root, "checkedOn", source, "");

        List<Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JsonArray questionArray = array(root, "questions", source, "");
        if (questionArray.isEmpty()) {
            throw invalid(source, "questions", "must not be empty");
        }
        for (int i = 0; i < questionArray.size(); i++) {
            String at = "questions[" + i + "]";
            JsonObject q = object(questionArray.get(i), source, at);
            keys(q, source, at, Set.of("id", "question", "answer", "links"));
            String id = string(q, "id", source, at);
            if (!ID.matcher(id).matches() || !ids.add(id) || id.equals("compare")) {
                throw invalid(source, at + ".id", "\"" + id + "\" must be lowercase words joined by -, unique and not \"compare\"");
            }
            String question = text(q, "question", source, at);
            List<String> answer = new ArrayList<>();
            JsonArray answerArray = array(q, "answer", source, at);
            if (answerArray.isEmpty()) {
                throw invalid(source, at + ".answer", "must have at least one paragraph");
            }
            for (int p = 0; p < answerArray.size(); p++) {
                answer.add(text(answerArray.get(p), source, at + ".answer[" + p + "]"));
            }
            List<Link> links = new ArrayList<>();
            JsonValue linkValue = q.get("links");
            if (linkValue != null) {
                JsonArray linkArray = array(q, "links", source, at);
                for (int l = 0; l < linkArray.size(); l++) {
                    String lat = at + ".links[" + l + "]";
                    JsonObject link = object(linkArray.get(l), source, lat);
                    keys(link, source, lat, Set.of("text", "path"));
                    String path = string(link, "path", source, lat);
                    if (!(path.startsWith("/") && !path.startsWith("//")) && !path.startsWith("https://")) {
                        throw invalid(source, lat + ".path", "\"" + path + "\" must be a path on this site or an https URL");
                    }
                    links.add(new Link(text(link, "text", source, lat), path));
                }
            }
            questions.add(new Question(id, question, List.copyOf(answer), List.copyOf(links)));
        }

        JsonObject c = object(root.get("comparison"), source, "comparison");
        keys(c, source, "comparison", Set.of("columns", "rows"));
        List<String> columns = new ArrayList<>();
        JsonArray columnArray = array(c, "columns", source, "comparison");
        if (columnArray.size() < 2) {
            throw invalid(source, "comparison.columns", "must name swapi.build and at least one other API");
        }
        for (int i = 0; i < columnArray.size(); i++) {
            columns.add(text(columnArray.get(i), source, "comparison.columns[" + i + "]"));
        }
        List<Row> rows = new ArrayList<>();
        JsonArray rowArray = array(c, "rows", source, "comparison");
        if (rowArray.isEmpty()) {
            throw invalid(source, "comparison.rows", "must not be empty");
        }
        for (int r = 0; r < rowArray.size(); r++) {
            String at = "comparison.rows[" + r + "]";
            JsonObject row = object(rowArray.get(r), source, at);
            keys(row, source, at, Set.of("label", "cells"));
            String label = text(row, "label", source, at);
            JsonArray cellArray = array(row, "cells", source, at);
            if (cellArray.size() != columns.size()) {
                throw invalid(source, at + ".cells", "has " + cellArray.size() + " cells for " + columns.size() + " columns");
            }
            List<Cell> cells = new ArrayList<>();
            for (int i = 0; i < cellArray.size(); i++) {
                String cat = at + ".cells[" + i + "]";
                JsonObject cell = object(cellArray.get(i), source, cat);
                keys(cell, source, cat, Set.of("value", "source", "checkedOn"));
                String src = string(cell, "source", source, cat);
                if (!src.startsWith("https://")) {
                    throw invalid(source, cat + ".source", "\"" + src + "\" must be an https URL");
                }
                cells.add(new Cell(text(cell, "value", source, cat), src, date(cell, "checkedOn", source, cat)));
            }
            rows.add(new Row(label, List.copyOf(cells)));
        }
        return new FaqFile(checkedOn, questions, new Comparison(List.copyOf(columns), List.copyOf(rows)));
    }

    /** A reader-facing string: no HTML, and every {marker} is a {count:type} of a known type. */
    private static String text(JsonObject o, String key, String source, String at) {
        return text(o.get(key), source, at.isEmpty() ? key : at + "." + key);
    }

    private static String text(JsonValue value, String source, String key) {
        if (!(value instanceof JsonString s) || s.getString().isBlank()) {
            throw invalid(source, key, "missing or not a non-empty string");
        }
        String text = s.getString();
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            throw invalid(source, key, "has < or >: no HTML in the file");
        }
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            Matcher count = COUNT.matcher(m.group(1));
            if (!count.matches() || !KnownEntities.TYPES.contains(count.group(1))) {
                throw invalid(source, key, "{" + m.group(1) + "} is not {count:<type>} with a type of " + KnownEntities.TYPES);
            }
        }
        return text;
    }

    private static LocalDate date(JsonObject o, String key, String source, String at) {
        String value = string(o, key, source, at);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(source, at.isEmpty() ? key : at + "." + key, "\"" + value + "\" is not an ISO date (yyyy-mm-dd)");
        }
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

    private static JsonObject object(JsonValue value, String source, String key) {
        if (!(value instanceof JsonObject object)) {
            throw invalid(source, key, "missing or not an object");
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
