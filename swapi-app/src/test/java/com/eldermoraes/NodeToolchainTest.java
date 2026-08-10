package com.eldermoraes;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the frontend toolchain: the Node version Quinoa installs must satisfy
 * every {@code engines.node} range in the committed package-lock.json.
 *
 * <p>Why this exists: {@code mvnw test} runs with Quinoa off ("Quinoa is disabled
 * by default in tests"), so no test in this suite ever installs Node or runs vite.
 * A Node floor that is too low for a bumped dependency therefore passes the whole
 * suite green and only blows up mid-release, in the deploy job. This test closes
 * that gap in milliseconds and without Node present, because package-lock.json —
 * which is committed — carries each package's engines.
 *
 * <p>It is a coherence test in the style of {@link ChangelogVersionTest} and
 * {@link ServerJsonVersionTest}: it proves two committed files still agree. It is
 * NOT a substitute for actually building the frontend — that is {@code mvnw
 * package}'s job, which CI runs.
 */
class NodeToolchainTest {

    private static final Pattern NODE_VERSION = Pattern.compile(
            "(?m)^quarkus\\.quinoa\\.package-manager-install\\.node-version=(.+)$");

    private static final Pattern EXACT_RELEASE = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    @Test
    void nodeVersionIsPinnedToAnExactRelease() {
        String declared = declaredNodeVersion();
        assertTrue(EXACT_RELEASE.matcher(declared).matches(),
                () -> "Quinoa needs a concrete X.Y.Z release to download, not a range "
                        + "or an alias — got '" + declared + "'");
    }

    /**
     * The real guard. Every package in the lockfile that declares an engines.node
     * range must accept the Node that Quinoa installs.
     */
    @Test
    void declaredNodeSatisfiesEveryEngineRangeInTheLockfile() {
        Version node = Version.parse(declaredNodeVersion());
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, JsonValue> entry : lockfilePackages().entrySet()) {
            if (entry.getValue().getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject pkg = entry.getValue().asJsonObject();
            JsonObject engines = pkg.getJsonObject("engines");
            if (engines == null || !engines.containsKey("node")) {
                continue;
            }
            String range = engines.getString("node");
            if (!SemverRange.accepts(node, range)) {
                violations.add("  %s (%s) requires node %s".formatted(
                        entry.getKey().isEmpty() ? "<root>" : entry.getKey(),
                        pkg.getString("version", "?"),
                        range));
            }
        }

        assertTrue(violations.isEmpty(),
                () -> ("Node " + declaredNodeVersion() + " (quarkus.quinoa.package-manager-install"
                        + ".node-version) is below what these packages require:\n"
                        + String.join("\n", violations)
                        + "\nRaise node-version in application.properties, or hold the "
                        + "dependency back in package.json. The suite cannot catch this any "
                        + "other way: Quinoa is disabled in tests, so nothing here runs Node."));
    }

    /**
     * Sanity check on the parser itself. Without this, a lockfile full of ranges
     * the parser silently mis-reads would keep the guard above green forever.
     */
    @Test
    void lockfileDeclaresEngineRangesAtAll() {
        long withEngines = lockfilePackages().values().stream()
                .filter(v -> v.getValueType() == JsonValue.ValueType.OBJECT)
                .map(JsonValue::asJsonObject)
                .filter(o -> o.getJsonObject("engines") != null
                        && o.getJsonObject("engines").containsKey("node"))
                .count();
        assertTrue(withEngines > 0,
                "no engines.node found in the lockfile — the guard above would be vacuous. "
                        + "Did the lockfile format change?");
    }

    // --- reading the two committed files ------------------------------------

    private static String declaredNodeVersion() {
        Path properties = ReleaseMetadata.repoRoot()
                .resolve("swapi-app/src/main/resources/application.properties");
        String text;
        try {
            text = Files.readString(properties);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + properties, e);
        }
        Matcher m = NODE_VERSION.matcher(text);
        assertTrue(m.find(), () -> "quarkus.quinoa.package-manager-install.node-version "
                + "not found in " + properties + ". If Quinoa stopped installing Node, "
                + "delete this test rather than leaving it passing on nothing.");
        return m.group(1).trim();
    }

    private static Map<String, JsonValue> lockfilePackages() {
        Path lockfile = ReleaseMetadata.repoRoot()
                .resolve("swapi-app/src/main/webui/package-lock.json");
        assertTrue(Files.isRegularFile(lockfile), () -> "lockfile not found at " + lockfile);
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(lockfile))) {
            JsonObject packages = reader.readObject().getJsonObject("packages");
            assertFalse(packages == null || packages.isEmpty(),
                    "lockfile has no 'packages' — expected lockfileVersion 2 or 3");
            return packages;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + lockfile, e);
        }
    }

    // --- the smallest semver subset that covers this lockfile ---------------

    /**
     * A version with the number of components that were actually written, so a
     * bare "18" can mean "any 18.x" while "18.18.0" is exact.
     */
    private record Version(int major, int minor, int patch, int specified)
            implements Comparable<Version> {

        static Version parse(String raw) {
            String[] parts = raw.trim().split("\\.");
            if (parts.length == 0 || parts.length > 3) {
                throw new IllegalArgumentException("unparseable version: '" + raw + "'");
            }
            int[] n = {0, 0, 0};
            for (int i = 0; i < parts.length; i++) {
                try {
                    n[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("unparseable version: '" + raw + "'", e);
                }
            }
            return new Version(n[0], n[1], n[2], parts.length);
        }

        @Override
        public int compareTo(Version other) {
            if (major != other.major) {
                return Integer.compare(major, other.major);
            }
            if (minor != other.minor) {
                return Integer.compare(minor, other.minor);
            }
            return Integer.compare(patch, other.patch);
        }
    }

    /**
     * Handles exactly the three forms this lockfile uses — {@code ^x.y.z},
     * {@code >=x[.y[.z]]} and a bare {@code x} — joined by {@code ||}. Anything
     * else throws instead of quietly returning false or true: a range this parser
     * does not understand must fail loudly, never turn the guard vacuous.
     */
    private static final class SemverRange {

        static boolean accepts(Version node, String range) {
            for (String term : range.split("\\|\\|")) {
                if (acceptsTerm(node, term.trim())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean acceptsTerm(Version node, String term) {
            if (term.startsWith(">=")) {
                return node.compareTo(Version.parse(term.substring(2))) >= 0;
            }
            if (term.startsWith("^")) {
                return caret(node, Version.parse(term.substring(1)));
            }
            if (Character.isDigit(term.charAt(0))) {
                // Bare "18" means 18.x.x; bare "13.7" means 13.7.x.
                Version floor = Version.parse(term);
                return node.compareTo(floor) >= 0 && samePrefix(node, floor);
            }
            throw new IllegalArgumentException(
                    "unsupported engines.node comparator: '" + term + "'. Teach "
                            + NodeToolchainTest.class.getSimpleName() + " this form — "
                            + "do not loosen the check.");
        }

        /** Caret allows changes that do not modify the left-most non-zero component. */
        private static boolean caret(Version node, Version floor) {
            if (node.compareTo(floor) < 0) {
                return false;
            }
            if (floor.major() > 0) {
                return node.major() == floor.major();
            }
            if (floor.minor() > 0) {
                return node.major() == 0 && node.minor() == floor.minor();
            }
            return node.major() == 0 && node.minor() == 0;
        }

        private static boolean samePrefix(Version node, Version floor) {
            if (node.major() != floor.major()) {
                return false;
            }
            return floor.specified() < 2 || node.minor() == floor.minor();
        }
    }
}
