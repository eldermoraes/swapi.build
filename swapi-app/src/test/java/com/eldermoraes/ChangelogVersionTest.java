package com.eldermoraes;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one release mistake that actually happens: bumping the pom without
 * writing the changelog entry. Plain JUnit — no Quarkus boot needed to read files.
 */
class ChangelogVersionTest {

    @Test
    void currentPomVersionHasANonEmptyDatedSection() {
        String version = ReleaseMetadata.pomVersion();
        String changelog = ReleaseMetadata.changelog();

        Pattern section = Pattern.compile(
                "^## \\[" + Pattern.quote(version) + "\\] - \\d{4}-\\d{2}-\\d{2}\\s*$(.*?)(?=^## |\\z)",
                Pattern.MULTILINE | Pattern.DOTALL);

        Matcher m = section.matcher(changelog);
        assertTrue(m.find(), () -> "CHANGELOG.md has no '## [" + version
                + "] - YYYY-MM-DD' section. Version bumped without a changelog entry? "
                + "See docs/RELEASE.md.");

        String body = m.group(1);
        assertTrue(body.contains("### "), () -> "section [" + version
                + "] has no category heading (### Added/Changed/Fixed/...)");
        assertFalse(body.replaceAll("(?m)^#.*$", "").replaceAll("\\s+", "").isEmpty(),
                () -> "section [" + version + "] is empty");
    }

    @Test
    void unreleasedSectionExists() {
        assertTrue(ReleaseMetadata.changelog().contains("## [Unreleased]"),
                "CHANGELOG.md must keep an '## [Unreleased]' section for work in flight");
    }

    /**
     * Every version that ever shipped, newest first. Two-digit entries are literal —
     * they match the pom of their day and the tag names. 1.0.0-SNAPSHOT is absent on
     * purpose: a snapshot is not a release.
     */
    private static final String[] RELEASED_VERSIONS = {
            "2.1.0", "2.0.2", "2.0.1", "2.0.0",
            "1.9.1", "1.9.0", "1.8.1", "1.8",
            "1.7", "1.3", "1.2", "1.1"
    };

    @Test
    void everyReleasedVersionIsDocumented() {
        String changelog = ReleaseMetadata.changelog();
        for (String version : RELEASED_VERSIONS) {
            Pattern section = Pattern.compile(
                    "^## \\[" + Pattern.quote(version) + "\\] - \\d{4}-\\d{2}-\\d{2}\\s*$",
                    Pattern.MULTILINE);
            assertTrue(section.matcher(changelog).find(),
                    () -> "no dated section for released version " + version);
            assertTrue(changelog.contains("[" + version + "]: https://github.com/"),
                    () -> "no link reference for version " + version);
        }
    }

    @Test
    void sectionsAreInDescendingOrder() {
        String changelog = ReleaseMetadata.changelog();
        int previous = -1;
        for (String version : RELEASED_VERSIONS) {
            int at = changelog.indexOf("## [" + version + "] - ");
            final int found = at;
            final int before = previous;
            assertTrue(found > before,
                    () -> "section [" + version + "] is out of order — newest first");
            previous = at;
        }
    }
}
