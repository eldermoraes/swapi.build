package com.eldermoraes.seo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The claims no page of the site makes (2.15.0, plan of slice G, section 12),
 * case-insensitive. Everywhere: a promise of uptime or a claim of being first,
 * alone or unique. About other APIs (the FAQ and its comparison): any word that
 * says or suggests they are not working. The bare words "only" and "first" stay
 * allowed: the legitimate text needs them ("MCP only", "the first is active").
 */
public final class ForbiddenClaims {

    public static final List<String> EVERYWHERE = List.of(
            "never go offline", "never goes offline", "100%", "the only", "the first", "unique");

    /** Whole words, so "download" or "markdown" are not "down". */
    public static final List<String> ABOUT_THIRD_PARTIES = List.of("down", "offline", "dead", "unreliable");

    private ForbiddenClaims() {
    }

    /** The phrases of {@link #EVERYWHERE} found in the text. */
    public static List<String> everywhere(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return EVERYWHERE.stream().filter(lower::contains).toList();
    }

    /** Every phrase of both lists found in the text (the words of the second as whole words). */
    public static List<String> aboutThirdParties(String text) {
        List<String> found = new ArrayList<>(everywhere(text));
        for (String word : ABOUT_THIRD_PARTIES) {
            if (Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(text).find()) {
                found.add(word);
            }
        }
        return found;
    }
}
