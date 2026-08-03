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
}
