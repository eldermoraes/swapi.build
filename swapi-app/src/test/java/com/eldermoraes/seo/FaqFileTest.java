package com.eldermoraes.seo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * seo/faq.json (2.15.0): the text of /faq and its dated comparison. No Quarkus
 * boot: the rules of the file are checked where it is read, and the rules of
 * age run against a fixed clock (the rule) and against today (the shipped file).
 *
 * shippedFactsAreAtMost180DaysOld can stop a release that does not touch the
 * FAQ. That is on purpose: an old comparison is the reputational risk. The fix
 * is to check the facts again and update their dates (docs/RELEASE.md).
 */
class FaqFileTest {

    private static final FaqFile SHIPPED = FaqFile.load();

    @Test
    void loadsTheShippedFile() {
        assertEquals(11, SHIPPED.questions().size());
        assertEquals(List.of("swapi.build", "swapi.dev", "swapi.tech", "swapi.info", "Pipeworx (swapi MCP)"),
                SHIPPED.comparison().columns());
        Set<String> labels = SHIPPED.comparison().rows().stream().map(FaqFile.Row::label).collect(Collectors.toSet());
        assertTrue(labels.contains("Public status page"), labels.toString());
    }

    @Test
    void everyCellHasValueSourceAndDate() {
        for (FaqFile.Row row : SHIPPED.comparison().rows()) {
            for (FaqFile.Cell cell : row.cells()) {
                assertFalse(cell.value().isBlank(), row.label());
                assertFalse(cell.source().isBlank(), row.label());
                assertTrue(cell.checkedOn() != null, row.label());
            }
        }
    }

    @Test
    void sourcesAreHttps() {
        for (FaqFile.Row row : SHIPPED.comparison().rows()) {
            for (FaqFile.Cell cell : row.cells()) {
                assertTrue(cell.source().startsWith("https://"), row.label() + ": " + cell.source());
            }
        }
        for (FaqFile.Question q : SHIPPED.questions()) {
            for (FaqFile.Link link : q.links()) {
                assertTrue(link.path().startsWith("/") || link.path().startsWith("https://"), q.id() + ": " + link.path());
            }
        }
    }

    @Test
    void datesAreIsoAndNotInTheFutureRule() {
        FaqFile dated = FaqFile.load("seo/faq-dated.json");
        assertEquals(LocalDate.parse("2026-01-01"), dated.checkedOn());
        assertTrue(dated.datesInTheFuture(today(fixed("2026-01-01"))).isEmpty(), "the same day is not the future");
        assertEquals(3, dated.datesInTheFuture(today(fixed("2025-12-31"))).size(), "every date is after the day before");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FaqFile.load("seo/invalid-faq-date.json"));
        assertTrue(error.getMessage().contains("comparison.rows[0].cells[1].checkedOn"), error.getMessage());
    }

    @Test
    void shippedDatesAreNotInTheFuture() {
        assertEquals(List.of(), SHIPPED.datesInTheFuture(today(Clock.systemUTC())));
    }

    @Test
    void comparisonFactsAreAtMost180DaysOldRule() {
        FaqFile dated = FaqFile.load("seo/faq-dated.json");
        LocalDate checked = LocalDate.parse("2026-01-01");
        assertEquals(List.of(), dated.factsOlderThanTheLimit(checked.plusDays(179)), "179 days: still fresh");
        assertEquals(List.of(), dated.factsOlderThanTheLimit(checked.plusDays(180)), "180 days: the last fresh day");
        List<FaqFile.Dated> stale = dated.factsOlderThanTheLimit(checked.plusDays(181));
        assertEquals(3, stale.size(), "181 days: every date is stale");
        assertTrue(stale.get(1).where().contains("REST JSON"), stale.get(1).where());
    }

    @Test
    void shippedFactsAreAtMost180DaysOld() {
        List<FaqFile.Dated> stale = SHIPPED.factsOlderThanTheLimit(today(Clock.systemUTC()));
        assertEquals(List.of(), stale, "check these facts again and update their dates in " + FaqFile.RESOURCE
                + " (docs/RELEASE.md)");
    }

    @Test
    void everyRowHasOneCellPerColumn() {
        int columns = SHIPPED.comparison().columns().size();
        for (FaqFile.Row row : SHIPPED.comparison().rows()) {
            assertEquals(columns, row.cells().size(), row.label());
        }
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FaqFile.load("seo/invalid-faq-cells.json"));
        assertTrue(error.getMessage().contains("comparison.rows[0].cells: has 1 cells for 2 columns"), error.getMessage());
    }

    @Test
    void theFourCompetitorsAreNamed() {
        String text = String.join("\n", SHIPPED.texts());
        for (String name : List.of("swapi.dev", "swapi.tech", "swapi.info", "Pipeworx")) {
            assertTrue(text.contains(name), name);
        }
        assertEquals("swapi.build", SHIPPED.comparison().columns().getFirst());
    }

    @Test
    void noForbiddenPhrases() {
        for (String text : SHIPPED.texts()) {
            assertEquals(List.of(), ForbiddenClaims.aboutThirdParties(text), text);
        }
        // The rule itself: each phrase is caught, whatever the case; "only",
        // "first" and words that merely contain "down" are not.
        assertEquals(List.of("never go offline", "offline"), ForbiddenClaims.aboutThirdParties("It will NEVER go offline"));
        assertEquals(List.of("100%"), ForbiddenClaims.aboutThirdParties("100% uptime"));
        assertEquals(List.of("the only"), ForbiddenClaims.aboutThirdParties("The only API"));
        assertEquals(List.of("down"), ForbiddenClaims.aboutThirdParties("swapi.x is down"));
        assertEquals(List.of(), ForbiddenClaims.aboutThirdParties("MCP only; the Markdown download comes first"));
    }

    @Test
    void countsAreMarkersFilledFromTheData() {
        Map<String, Integer> counts = Map.of("people", 82, "films", 6);
        assertEquals("82 people.", FaqFile.fill(FaqFile.load("seo/faq-dated.json").questions().getFirst().answer().getFirst(),
                counts::get));
        String data = SHIPPED.questions().stream().filter(q -> q.id().equals("data")).findFirst().orElseThrow()
                .answer().getFirst();
        assertTrue(data.contains("{count:people} people"), data);
        assertFalse(data.matches("(?s).*\\b\\d+ (films|planets|species|starships|vehicles)\\b.*"), data);
    }

    @ParameterizedTest
    @CsvSource({
            "seo/invalid-faq-unknown-key.json, questions[0].html: unknown key",
            "seo/invalid-faq-cells.json, comparison.rows[0].cells: has 1 cells for 2 columns",
            "seo/invalid-faq-source.json, comparison.rows[0].cells[1].source: \"http://swapi.dev/api/people/1/\" must be an https URL",
            "seo/invalid-faq-date.json, comparison.rows[0].cells[1].checkedOn: \"01/02/2026\" is not an ISO date",
            "seo/invalid-faq-html.json, questions[0].answer[1]: has < or >",
            "seo/invalid-faq-marker.json, questions[0].answer[0]: {count:droids} is not {count:<type>}",
            "seo/invalid-faq-link.json, questions[0].links[0].path: \"javascript:alert(1)\" must be a path on this site or an https URL",
            "seo/missing-faq.json, seo/missing-faq.json is missing from the classpath",
    })
    void invalidFilesFailWithTheKey(String file, String expected) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> FaqFile.load(file));
        assertTrue(error.getMessage().startsWith(file), error.getMessage());
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private static Clock fixed(String day) {
        return Clock.fixed(LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private static LocalDate today(Clock clock) {
        return LocalDate.now(clock);
    }
}
