package com.eldermoraes.seo.ssr;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Iterator;
import java.util.Map;

/**
 * JSON text exactly as the client writes it, {@code JSON.stringify(value, null, 2)}
 * (json-highlight.ts), for the record in the template of detail (2.12.0). The
 * services format with JSON-B (Parsson, four spaces), so the server reformats
 * here: two spaces, {@code ": "} after a key, {@code {}} and {@code []} when
 * empty, key order kept, and JavaScript's string escapes (the two-character
 * forms for \b \f \n \r \t, \\u00xx in lower case for the other controls,
 * nothing else escaped). The client then colours the text without changing a
 * character, so nothing moves. Numbers: the data has only integers, which print
 * as JavaScript prints them; any other number keeps its JSON-P text.
 */
public final class JsonText {

    private JsonText() {
    }

    public static String pretty(JsonValue value) {
        StringBuilder out = new StringBuilder();
        write(out, value, "");
        return out.toString();
    }

    private static void write(StringBuilder out, JsonValue value, String indent) {
        switch (value.getValueType()) {
            case OBJECT -> {
                JsonObject object = value.asJsonObject();
                if (object.isEmpty()) {
                    out.append("{}");
                    return;
                }
                String inner = indent + "  ";
                out.append("{\n");
                Iterator<Map.Entry<String, JsonValue>> entries = object.entrySet().iterator();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonValue> entry = entries.next();
                    out.append(inner);
                    string(out, entry.getKey());
                    out.append(": ");
                    write(out, entry.getValue(), inner);
                    out.append(entries.hasNext() ? ",\n" : "\n");
                }
                out.append(indent).append('}');
            }
            case ARRAY -> {
                JsonArray array = value.asJsonArray();
                if (array.isEmpty()) {
                    out.append("[]");
                    return;
                }
                String inner = indent + "  ";
                out.append("[\n");
                for (int i = 0; i < array.size(); i++) {
                    out.append(inner);
                    write(out, array.get(i), inner);
                    out.append(i < array.size() - 1 ? ",\n" : "\n");
                }
                out.append(indent).append(']');
            }
            case STRING -> string(out, ((JsonString) value).getString());
            case NUMBER -> {
                JsonNumber number = (JsonNumber) value;
                out.append(number.isIntegral() ? number.bigIntegerValue().toString() : number.toString());
            }
            case TRUE -> out.append("true");
            case FALSE -> out.append("false");
            case NULL -> out.append("null");
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || (Character.isSurrogate(c) && !pairedSurrogate(s, i))) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** JSON.stringify escapes a lone surrogate (well-formed JSON.stringify, ES2019). */
    private static boolean pairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }
}
