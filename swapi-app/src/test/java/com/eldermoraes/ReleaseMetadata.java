package com.eldermoraes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads release metadata straight from the working tree: the pom version and the
 * repo-root CHANGELOG.md. The suite runs from swapi-app/, so both are found by
 * walking up from user.dir.
 */
final class ReleaseMetadata {

    private static final Pattern POM_VERSION = Pattern.compile(
            "<artifactId>swapi-app</artifactId>\\s*<version>([^<]+)</version>");

    private ReleaseMetadata() {
    }

    static Path repoRoot() {
        List<Path> searched = new ArrayList<>();
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            searched.add(dir);
            if (Files.isRegularFile(dir.resolve("CHANGELOG.md"))
                    && Files.isDirectory(dir.resolve("swapi-app"))) {
                return dir;
            }
            // Stop at the checkout root. A git worktree lives inside the main
            // checkout's .claude/worktrees/, so walking past its own root would
            // find the parent repo's CHANGELOG.md and pass on the wrong file.
            // In a worktree .git is a file; in a normal checkout, a directory.
            if (Files.exists(dir.resolve(".git"))) {
                break;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repo root with CHANGELOG.md not found. Searched: " + searched);
    }

    static Path changelogPath() {
        return repoRoot().resolve("CHANGELOG.md");
    }

    static String changelog() {
        try {
            return Files.readString(changelogPath());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + changelogPath(), e);
        }
    }

    static String pomVersion() {
        Path pom = repoRoot().resolve("swapi-app").resolve("pom.xml");
        String text;
        try {
            text = Files.readString(pom);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + pom, e);
        }
        Matcher m = POM_VERSION.matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("swapi-app <version> not found in " + pom);
        }
        return m.group(1).trim();
    }
}
