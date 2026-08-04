# Changelog and Release Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give swapi.build a retroactive `CHANGELOG.md`, 12 backdated git tags with matching GitHub Releases, a forward release runbook, and two tests that keep the pom version, the changelog and the published OpenAPI version from drifting apart.

**Architecture:** Four independent units — a Keep a Changelog file at the repo root, a runbook at `docs/RELEASE.md`, two JUnit tests in the existing `swapi-app` suite, and a one-shot tagging/release operation run on `main` after merge. No CI, no new dependency, no version bump, no deploy.

**Tech Stack:** Markdown (Keep a Changelog 1.1.0), git annotated tags, `gh` CLI, JUnit 5 + RestAssured + Quarkus test harness already in `swapi-app`.

**Spec:** `docs/superpowers/specs/2026-08-03-changelog-release-management-design.md`

## Global Constraints

- Changelog is **English**; specs/plans stay **pt-BR**. Runbook follows `docs/DEPLOY.md` tone (numbered steps + troubleshooting table).
- Format: Keep a Changelog 1.1.0, categories limited to `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`. Infra items go under `Changed`; dev-dependency patches under `Security`.
- Tag names are **literal to the pom**: `v1.1`, `v1.2`, `v1.3`, `v1.7`, `v1.8`, `v1.8.1`, `v1.9.0`, `v1.9.1`, `v2.0.0`, `v2.0.1`, `v2.0.2`, `v2.1.0`. Twelve tags. No tag for `1.0.0-SNAPSHOT`.
- Every tag is **annotated** with `GIT_COMMITTER_DATE` set to the pointed commit's date.
- Section dates are the **`tag_at` commit date**, not the bump date.
- **Do not bump `swapi-app/pom.xml`.** It stays `2.1.0`. No deploy in this plan.
- Tests run with `cd swapi-app && ./mvnw test` (port 8081). Never run `mvn clean` while dev mode is running.
- Repo URL for links: `https://github.com/eldermoraes/swapi.build`.
- Steps 6 and 7 (tag push, releases) are **externally visible** and require explicit user confirmation at execution time, even though this plan is approved.

## Tag targets (verified 2026-08-03)

| Tag | `tag_at` | Section date |
|---|---|---|
| `v1.1` | `3491418` | 2025-05-29 |
| `v1.2` | `0f293de` | 2025-06-02 |
| `v1.3` | `059db1c` | 2025-06-03 |
| `v1.7` | `42f062e` | 2025-08-05 |
| `v1.8` | `0815cd1` | 2026-03-09 |
| `v1.8.1` | `a0c43f5` | 2026-07-31 |
| `v1.9.0` | `de60e16` | 2026-08-01 |
| `v1.9.1` | `9fe7a68` | 2026-08-02 |
| `v2.0.0` | `c9f4e0b` | 2026-08-02 |
| `v2.0.1` | `2beb46c` | 2026-08-02 |
| `v2.0.2` | `de2192f` | 2026-08-03 |
| `v2.1.0` | `3fa39e0` | 2026-08-03 |

## File Structure

| File | Responsibility |
|---|---|
| `CHANGELOG.md` (create, repo root) | Public release history, Unreleased section, compare links |
| `docs/RELEASE.md` (create) | Forward release process runbook |
| `swapi-app/src/test/java/com/eldermoraes/ReleaseMetadata.java` (create) | Test-scope helper: locate repo root, read pom version, read changelog |
| `swapi-app/src/test/java/com/eldermoraes/ChangelogVersionTest.java` (create) | Guard: current pom version has a non-empty dated changelog section; all 12 released versions still documented |
| `swapi-app/src/test/java/com/eldermoraes/OpenApiVersionTest.java` (create) | Guard: `/openapi.json` `info.version` equals the pom version |
| `README.md` (modify) | Link changelog + releases |
| `CLAUDE.md` (modify) | Release step in the dev cycle |
| `docs/DEPLOY.md` (modify) | Prerequisite note pointing at `RELEASE.md` |

Tests live flat in `com.eldermoraes`, matching the existing suite (`OpenApiContractTest`, `CacheHeadersTest`, …).

---

### Task 1: Guard test + changelog skeleton

**Files:**
- Create: `swapi-app/src/test/java/com/eldermoraes/ReleaseMetadata.java`
- Create: `swapi-app/src/test/java/com/eldermoraes/ChangelogVersionTest.java`
- Create: `CHANGELOG.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `ReleaseMetadata.repoRoot() -> java.nio.file.Path`, `ReleaseMetadata.changelogPath() -> Path`, `ReleaseMetadata.changelog() -> String`, `ReleaseMetadata.pomVersion() -> String`. Task 2 reuses `changelog()`; Task 3 reuses `pomVersion()`.

- [ ] **Step 1: Write the failing test**

`swapi-app/src/test/java/com/eldermoraes/ReleaseMetadata.java`:

```java
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
```

`swapi-app/src/test/java/com/eldermoraes/ChangelogVersionTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd swapi-app && ./mvnw test -Dtest=ChangelogVersionTest`
Expected: FAIL — `IllegalStateException: repo root with CHANGELOG.md not found. Searched: [...]` (the file does not exist yet).

- [ ] **Step 3: Write the minimal `CHANGELOG.md`**

Only the header, `Unreleased`, the `2.1.0` section and the two links needed by them. The other eleven sections arrive in Task 2.

```markdown
# Changelog

All notable changes to swapi.build are documented here.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Versions up to and including 2.1.0 were reconstructed retroactively from git history
in August 2026 — the project had no changelog and no tags before then. Each version's
entry covers the commits between its tag and the previous one, so a change appears
under the version whose line of development contains it. Where a version bump
*declared* something implemented in the previous line, the entry says so.

The version served in [`/openapi.json`](https://swapi.build/openapi.json) (`info.version`)
is inherited from `swapi-app/pom.xml`, so it always matches the latest released version.

## [Unreleased]

### Added

- `CHANGELOG.md`, reconstructed retroactively down to 1.1.
- `docs/RELEASE.md`: the release process — version bump, changelog entry, tag,
  GitHub Release, deploy.
- Retroactive git tags `v1.1` … `v2.1.0` and matching GitHub Releases.
- Tests keeping the pom version, the changelog and the published OpenAPI version in sync.

## [2.1.0] - 2026-08-03

### Changed

- MCP serves stateful and stateless clients on the same `/mcp` endpoint. An unknown
  or missing `Mcp-Session-Id` is now served with a throwaway session instead of
  `404` (`quarkus.mcp.server.http.streamable.auto-init=true`), which is what makes
  stateful clients reliable on a platform without session affinity. `GET /mcp`
  answers `405`, `DELETE /mcp` answers `204`, and the legacy HTTP+SSE transport at
  `/mcp/sse` is rejected on purpose.
- The site presents swapi.build as both a REST API and an MCP server.

[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/eldermoraes/swapi.build/compare/v2.0.2...v2.1.0
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd swapi-app && ./mvnw test -Dtest=ChangelogVersionTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Verify the guard actually guards (negative test)**

```bash
cd swapi-app
sed -i '' 's|<version>2.1.0</version>|<version>2.1.1</version>|' pom.xml
./mvnw test -Dtest=ChangelogVersionTest   # must FAIL: "no '## [2.1.1] - YYYY-MM-DD' section"
git checkout pom.xml
./mvnw test -Dtest=ChangelogVersionTest   # PASS again
git diff --exit-code pom.xml              # must be clean
```

Expected: fail then pass, and `pom.xml` unchanged at the end. If the first run passes, the guard is worthless — stop and fix it before continuing.

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md swapi-app/src/test/java/com/eldermoraes/ReleaseMetadata.java \
        swapi-app/src/test/java/com/eldermoraes/ChangelogVersionTest.java
git commit -m "test: guard that the pom version has a changelog entry

Adds CHANGELOG.md (Unreleased + 2.1.0) and a plain-JUnit guard that fails when
swapi-app/pom.xml carries a version with no dated section in the changelog."
```

---

### Task 2: Retroactive sections, 2.0.2 down to 1.1

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `swapi-app/src/test/java/com/eldermoraes/ChangelogVersionTest.java`

**Interfaces:**
- Consumes: `ReleaseMetadata.changelog()` from Task 1.
- Produces: nothing new; Task 7 reads the finished sections to build release notes.

- [ ] **Step 1: Write the failing test**

Append to `ChangelogVersionTest`:

```java
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
            assertTrue(at > previous,
                    () -> "section [" + version + "] is out of order — newest first");
            previous = at;
        }
    }
```

Note the two-digit versions (`1.8`, `1.7`, `1.3`, `1.2`, `1.1`) are literal, matching the pom and the tag names. `1.0.0-SNAPSHOT` is absent on purpose.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd swapi-app && ./mvnw test -Dtest=ChangelogVersionTest`
Expected: FAIL — `no dated section for released version 2.0.2`.

- [ ] **Step 3: Write the eleven remaining sections**

Insert below the `## [2.1.0]` section, in this order. Each entry is derived from `git log --first-parent <previous_tag_at>..<tag_at>`; nothing here is invented.

```markdown
## [2.0.2] - 2026-08-03

### Added

- OpenAPI spec served at `/openapi.json`, and made the single source of API
  documentation: annotations on every resource and entity, an `info` block, and a
  guard that the advertised server URL is absolute.
- `/docs` page rendered from the OpenAPI spec, with a try-it control on every
  endpoint.
- Edge cache headers on `/api` responses (`CacheControlFilter`), with
  `Vary: Origin` so a CORS-echoed origin can never be served to another caller.

### Security

- Patched high-severity advisories in development dependencies (`npm audit fix`).
  No runtime dependency was affected.

## [2.0.1] - 2026-08-02

### Changed

- Version alignment only: `swapi-app/pom.xml` and the frontend `package.json`
  carry the same number again. No functional change.

## [2.0.0] - 2026-08-02

### Added

- Privacy Policy and Terms of Use pages.

### Changed

- **Breaking, formally declared here.** The public contract is: successful `GET`s
  return `200` (the historic `202` quirk is retired), ids are the record ids from
  each entity's `url` field, and unknown or non-numeric ids return `404`. The
  change was implemented in the 1.9.1 line on 2026-08-01 — see that entry — and
  this major bump is where it was published as a contract change.
- The public base URL lives in a per-request context, and the entities became
  read-only records.
- README rewritten to match the project as it actually is: `mcp` package, legal
  pages, the 200/404 contract, Apache 2.0.

## [1.9.1] - 2026-08-02

### Changed

- Successful `GET`s return `200` instead of `202`. Published as [2.0.0].
- Ids are the record ids from each entity's `url` field, including
  `/api/films/{id}` and the MCP `sw_get` tool for `FILMS`, which no longer looks
  up films by episode id.
- `People.homeworld` emits an absolute URL like every other link.
- The public base URL is derived from the active request, honoring
  `X-Forwarded-Proto` and `X-Forwarded-Host`, with `swapi.public-base-url` demoted
  to an override. Fixes the first request freezing the base URL for the process
  lifetime.

### Fixed

- Nonexistent ids return `404` instead of a success status.
- Non-numeric ids return `404`, via `int` path params.
- The frontend shows the real HTTP status instead of a hardcoded `200`.

### Added

- `CLAUDE.md` (development cycle and non-negotiables) and `docs/DEPLOY.md`
  (canonical deploy runbook).

## [1.9.0] - 2026-08-01

### Added

- `/docs/mcp` page with per-client setup tabs, a nav link and a callout on the
  home page.
- README section for the MCP server: spec emphasis, tools table, per-client
  guides.

### Changed

- MCP tool hints corrected — `destructiveHint=false` on the read-only tools.

## [1.8.1] - 2026-07-31

### Added

- **MCP server** at `/mcp`, exposing the same data as generic read-only tools over
  the in-memory services, with stateless conformance and REST regression tests.
  The version bump that named it shipped as [1.9.0].

### Changed

- **Deployment moved from DigitalOcean to Vercel** (container function) behind
  Cloudflare DNS: multi-stage native Dockerfile, UBI9 micro runtime matched to the
  Mandrel ubi9 builder's glibc, HTTP port read from `PORT`, immutable cache headers
  on hashed assets, and native build heap parameterized via `NATIVE_XMX`.
- Upgraded to Quarkus 3.33.3 LTS, Java 25 LTS, Quinoa 2.8.3 and Mandrel jdk-25.

### Removed

- Legacy scaffold Dockerfiles, superseded by `Dockerfile.vercel`.

### Fixed

- Wrong ID references across the project.

## [1.8] - 2026-03-09

### Added

- Frontend SPA in TypeScript + Vite with History API routing, served by Quinoa.

### Changed

- Backend services and configuration adjusted for the Quinoa integration.

## [1.7] - 2025-08-05

### Added

- A separate `swapi-ui` module, then abandoned within the same version line.

### Changed

- Fixes to make the application work under native compilation, including moving
  the JSON datasets to `src/main/resources/data`.

### Removed

- The `swapi-ui` module and its Web Bundler assets.

## [1.3] - 2025-06-03

### Changed

- Native image build settings in `application.properties`.

## [1.2] - 2025-06-02

### Added

- Services and resources for the remaining domains.

### Removed

- The generated `GreetingResource` scaffolding and its tests.

## [1.1] - 2025-05-29

### Added

- Native image builder properties.

### Fixed

- Id handling across all domains.
```

Replace the link block at the bottom of the file with:

```markdown
[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/eldermoraes/swapi.build/compare/v2.0.2...v2.1.0
[2.0.2]: https://github.com/eldermoraes/swapi.build/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/eldermoraes/swapi.build/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/eldermoraes/swapi.build/compare/v1.9.1...v2.0.0
[1.9.1]: https://github.com/eldermoraes/swapi.build/compare/v1.9.0...v1.9.1
[1.9.0]: https://github.com/eldermoraes/swapi.build/compare/v1.8.1...v1.9.0
[1.8.1]: https://github.com/eldermoraes/swapi.build/compare/v1.8...v1.8.1
[1.8]: https://github.com/eldermoraes/swapi.build/compare/v1.7...v1.8
[1.7]: https://github.com/eldermoraes/swapi.build/compare/v1.3...v1.7
[1.3]: https://github.com/eldermoraes/swapi.build/compare/v1.2...v1.3
[1.2]: https://github.com/eldermoraes/swapi.build/compare/v1.1...v1.2
[1.1]: https://github.com/eldermoraes/swapi.build/releases/tag/v1.1
```

Also add, right above the `## [1.1]` section:

```markdown
<!-- Earlier history (1.0.0-SNAPSHOT, 2025-05-28/29): initial commit, the JSON
datasets for all six resources, and the first REST resources. Not tagged — a
snapshot version is not a release. -->
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd swapi-app && ./mvnw test -Dtest=ChangelogVersionTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Cross-check the entries against history**

For each range, confirm nothing user-facing in the log is missing from the section:

```bash
for r in "3491418 0f293de 1.2" "0f293de 059db1c 1.3" "059db1c 42f062e 1.7" \
         "42f062e 0815cd1 1.8" "0815cd1 a0c43f5 1.8.1" "a0c43f5 de60e16 1.9.0" \
         "de60e16 9fe7a68 1.9.1" "9fe7a68 c9f4e0b 2.0.0" "c9f4e0b 2beb46c 2.0.1" \
         "2beb46c de2192f 2.0.2" "de2192f 3fa39e0 2.1.0"; do
  set -- $r; echo "===== $3 ====="; git log --first-parent --format='  %ad %s' --date=short "$1..$2"
done
```

Spec/plan/gitignore commits are deliberately not in the changelog — they ship no
behavior. Everything else must map to a bullet.

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md swapi-app/src/test/java/com/eldermoraes/ChangelogVersionTest.java
git commit -m "docs: reconstruct the changelog back to 1.1

Eleven retroactive sections derived from the commit range of each version line,
plus tests that every released version stays documented and in order."
```

---

### Task 3: OpenAPI version sync test

**Files:**
- Create: `swapi-app/src/test/java/com/eldermoraes/OpenApiVersionTest.java`

**Interfaces:**
- Consumes: `ReleaseMetadata.pomVersion()` from Task 1.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The published spec version must equal the released version. Quarkus derives
 * info.version from the pom today; this test exists so that hardcoding
 * quarkus.smallrye-openapi.info-version can never silently desync the public
 * spec from the releases.
 */
@QuarkusTest
class OpenApiVersionTest {

    @Test
    void publishedSpecVersionMatchesThePomVersion() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body("info.version", is(ReleaseMetadata.pomVersion()));
    }
}
```

- [ ] **Step 2: Run it and read the result carefully**

Run: `cd swapi-app && ./mvnw test -Dtest=OpenApiVersionTest`
Expected: PASS — the behavior already holds; this is a regression guard, so a green
first run is the correct outcome. Prove it is not vacuous in Step 3.

- [ ] **Step 3: Prove the guard bites**

```bash
cd swapi-app
printf '\nquarkus.smallrye-openapi.info-version=9.9.9\n' >> src/main/resources/application.properties
./mvnw test -Dtest=OpenApiVersionTest   # must FAIL: expected 2.1.0 but was 9.9.9
git checkout src/main/resources/application.properties
./mvnw test -Dtest=OpenApiVersionTest   # PASS
git diff --exit-code src/main/resources/application.properties
```

Expected: fail then pass, properties file clean. If the injected value does not
fail the test, the assertion is reading the wrong field — fix before continuing.

- [ ] **Step 4: Commit**

```bash
git add swapi-app/src/test/java/com/eldermoraes/OpenApiVersionTest.java
git commit -m "test: /openapi.json info.version must equal the pom version"
```

---

### Task 4: Release runbook and cross-links

**Files:**
- Create: `docs/RELEASE.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/DEPLOY.md`

**Interfaces:**
- Consumes: the changelog structure from Tasks 1–2, the test names from Tasks 1 and 3.
- Produces: the procedure Tasks 6 and 7 follow.

- [ ] **Step 1: Write `docs/RELEASE.md`**

````markdown
# Release runbook

Canonical release procedure. A release is a version number, a changelog entry, a
tag, a GitHub Release and a deploy — in that order. Deploying without the first
four is how a version reaches production undocumented.

There is deliberately **no CI automation**: no workflow, no Release Please. The
only failure mode that actually happens — bumping the version and forgetting the
changelog — is caught by `ChangelogVersionTest` in the normal test run.

## 1. Pick the version

Semantic Versioning. A change to the **public contract is major**: the precedent
is 2.0.0, where successful `GET`s went from `202` to `200`, ids became record ids
and unknown ids started returning `404`. New endpoints or capabilities are minor.
Fixes and dependency patches are patch.

## 2. Bump the version

`swapi-app/pom.xml` → `<version>` of `swapi-app`. If the frontend
`src/main/webui/package.json` carries a version, align it (that is all 2.0.1 was).

## 3. Write the changelog entry

Promote `## [Unreleased]` content into a new `## [x.y.z] - YYYY-MM-DD` section in
`CHANGELOG.md`, keep an empty `Unreleased` above it, and add the compare link at
the bottom of the file:

```
[x.y.z]: https://github.com/eldermoraes/swapi.build/compare/v<previous>...vx.y.z
```

Categories: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security` only.
Infrastructure goes under `Changed`; development-dependency patches under
`Security`. Mark contract breaks as **Breaking** in the text.

## 4. Run the suite

```bash
cd swapi-app && ./mvnw test
```

`ChangelogVersionTest` fails if steps 2 and 3 disagree. `OpenApiVersionTest` fails
if the published `info.version` stops matching the pom.

## 5. Commit and merge to `main`

Never release from a feature branch: the tag must point at `main`.

## 6. Tag

Annotated, on `main`, after the merge:

```bash
git tag -a vx.y.z -m "x.y.z — <one-line summary from the changelog>"
git push origin vx.y.z
```

## 7. GitHub Release

Notes are the changelog section, verbatim — never rewritten by hand:

```bash
awk '/^## \[x.y.z\] - /{f=1; next} /^## /{f=0} f' CHANGELOG.md \
  | gh release create vx.y.z --title "vx.y.z" --notes-file -
```

## 8. Deploy

A version bump changes `info.version` in the public spec, so it must reach
production: follow `docs/DEPLOY.md` exactly — preview → verify → production.

## 9. Verify

```bash
curl -s https://swapi.build/openapi.json | grep -o '"version":"[^"]*"' | head -1
```

Expect the version just released. Then the post-deploy checks in `docs/DEPLOY.md`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ChangelogVersionTest` fails with "no '## [x.y.z] - YYYY-MM-DD' section" | The pom was bumped without a changelog entry, or the date format is wrong (must be `YYYY-MM-DD`). |
| `ChangelogVersionTest` fails with "section is empty" | The section exists but has no category heading or no bullets. An intentionally empty release still needs a `### Changed` line saying so — see 2.0.1. |
| `OpenApiVersionTest` fails | Someone set `quarkus.smallrye-openapi.info-version` in `application.properties`. Remove it: the version must be inherited from the pom. |
| `gh release create` fails with "release already exists" | The release was created earlier. Use `gh release edit vx.y.z --notes-file -` instead. |
| Released version not visible at `/openapi.json` | The tag exists but the deploy did not run. Deploys are CLI-only — `git push` publishes commits and nothing else. See `docs/DEPLOY.md`. |
| The GitHub Release date is wrong on the retroactive releases | Expected. `gh release create` cannot backdate, so the twelve releases created on 2026-08-03 all carry that date; only the tags are dated correctly. |
````

- [ ] **Step 2: Link the changelog from `README.md`**

Add a section right before the license section (or at the end of the document if
there is no license section):

```markdown
## Changelog and releases

Release history lives in [CHANGELOG.md](CHANGELOG.md). Tagged releases are on the
[Releases page](https://github.com/eldermoraes/swapi.build/releases). The version
served in `/openapi.json` (`info.version`) is always the latest released version.
```

- [ ] **Step 3: Add the release step to the dev cycle in `CLAUDE.md`**

In the "Development cycle" list, insert between **Merge** (6) and **Push** (7),
renumbering the steps that follow:

```markdown
7. **Release** — if the change carries a version bump: version → changelog entry →
   tag → GitHub Release, per `docs/RELEASE.md`.
```

And add to "Non-negotiable facts":

```markdown
- **A version bump is a release.** Bump → `CHANGELOG.md` entry → annotated tag →
  GitHub Release → deploy, per `docs/RELEASE.md`. `ChangelogVersionTest` fails the
  suite if the pom version has no changelog section.
```

- [ ] **Step 4: Point `docs/DEPLOY.md` at the runbook**

Add to the Prerequisites list:

```markdown
- If this deploy carries a version bump, `docs/RELEASE.md` steps 1–7 are done
  (changelog entry, tag and GitHub Release exist).
```

- [ ] **Step 5: Verify the whole suite is green**

Run: `cd swapi-app && ./mvnw test`
Expected: BUILD SUCCESS, zero failures, including the three new test classes.

- [ ] **Step 6: Commit**

```bash
git add docs/RELEASE.md README.md CLAUDE.md docs/DEPLOY.md
git commit -m "docs: release runbook, and wire it into the dev cycle

Adds docs/RELEASE.md (version, changelog, tag, release, deploy, verify) and links
it from README, CLAUDE.md and the deploy runbook."
```

---

### Task 5: Merge to `main`

**Files:** none (git operation)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: the `main` commit that Tasks 6 and 7 tag and release from.

- [ ] **Step 1: Confirm the branch is clean and green**

```bash
git status --porcelain          # empty
cd swapi-app && ./mvnw test     # BUILD SUCCESS
```

- [ ] **Step 2: Ask the user how to integrate**

Use `superpowers:finishing-a-development-branch`: local merge, PR, or keep the
branch. Do not choose unilaterally.

- [ ] **Step 3: Merge, then re-run the suite on the merged result**

Run: `cd swapi-app && ./mvnw test`
Expected: BUILD SUCCESS on `main`.

- [ ] **Step 4: Record the merged HEAD**

```bash
git rev-parse --short HEAD
```

Note it. If the merge added a commit on top of `3fa39e0`, `v2.1.0` still points at
`3fa39e0` — the tag marks the released code, and the changelog itself is
`Unreleased` work.

---

### Task 6: Create and push the twelve tags

**Files:** none (git operation)

**Interfaces:**
- Consumes: the tag table in this plan; the changelog sections for tag messages.
- Produces: `v1.1` … `v2.1.0` on `origin`, which Task 7 turns into releases.

- [ ] **Step 1: Ask for explicit confirmation**

Pushing tags is public and awkward to undo. State exactly what will be pushed
(twelve tags, names and targets) and wait for a yes. Do not proceed on the
strength of the approved plan alone.

- [ ] **Step 2: Create the twelve annotated tags locally, backdated**

Run from the repo root, on `main`:

```bash
create_tag() {  # name  commit  message
  GIT_COMMITTER_DATE="$(git log -1 --format=%aI "$2")" \
    git tag -a "$1" "$2" -m "$3"
}
create_tag v1.1   3491418 "1.1 — id handling fixed across domains; native image builder properties"
create_tag v1.2   0f293de "1.2 — services and resources for the remaining domains"
create_tag v1.3   059db1c "1.3 — native image build settings"
create_tag v1.7   42f062e "1.7 — native compilation fixes; swapi-ui module added and dropped"
create_tag v1.8   0815cd1 "1.8 — TypeScript/Vite SPA served by Quinoa"
create_tag v1.8.1 a0c43f5 "1.8.1 — MCP server; DigitalOcean to Vercel/Cloudflare; Quarkus 3.33.3 LTS, Java 25"
create_tag v1.9.0 de60e16 "1.9.0 — MCP documentation: /docs/mcp page and README section"
create_tag v1.9.1 9fe7a68 "1.9.1 — 200 on success, record ids, 404s; per-request base URL discovery"
create_tag v2.0.0 c9f4e0b "2.0.0 — public contract change declared; legal pages; per-request base-url context"
create_tag v2.0.1 2beb46c "2.0.1 — version alignment only"
create_tag v2.0.2 de2192f "2.0.2 — OpenAPI as the single documentation source; /docs page; edge cache headers"
create_tag v2.1.0 3fa39e0 "2.1.0 — MCP serves stateful and stateless clients on one endpoint"
```

- [ ] **Step 3: Verify every tag before pushing**

```bash
for t in $(git tag -l | sort -V); do
  v="${t#v}"
  pom=$(git show "$t:swapi-app/pom.xml" | grep -m1 -A3 '<artifactId>swapi-app</artifactId>' \
        | grep -m1 '<version>' | sed -E 's@.*<version>(.*)</version>.*@\1@')
  [ "$pom" = "$v" ] && ok=OK || ok="MISMATCH($pom)"
  printf '%-8s %s  tagger=%s  %s\n' "$t" "$(git rev-parse --short "$t^{commit}")" \
    "$(git log -1 --format=%ad --date=short "$t^{commit}")" "$ok"
done
git tag -l | wc -l    # 12
```

Expected: twelve lines, every one `OK`. A `MISMATCH` means a tag points at the
wrong commit — delete it (`git tag -d`) and recreate it. **Do not push with any
mismatch.**

- [ ] **Step 4: Push the tags**

```bash
git push origin --tags
git ls-remote --tags origin | grep -vc '\^{}'   # 12
```

The `grep -v` is required: an annotated tag appears twice in `ls-remote` (the ref
and its dereferenced `^{}` line), so a raw count reads 24 and looks like a bug.

---

### Task 7: Create the twelve GitHub Releases and close the issue

**Files:** none (`gh` operations)

**Interfaces:**
- Consumes: the pushed tags from Task 6; the changelog sections from Tasks 1–2.
- Produces: twelve GitHub Releases; issue #2 closed.

- [ ] **Step 1: Ask for explicit confirmation**

Twelve releases notify everyone watching the repository. Confirm before the first
one, separately from the tag push.

- [ ] **Step 2: Dry-run the notes extraction**

```bash
awk '/^## \[2\.0\.0\] - /{f=1; next} /^## /{f=0} f' CHANGELOG.md
```

Expected: exactly the 2.0.0 body, no neighboring sections, no trailing `## `
header. If it bleeds into another section, fix the awk range before creating
anything.

- [ ] **Step 3: Create the twelve releases, oldest first**

Oldest first so that `v2.1.0` ends up flagged Latest:

```bash
for v in 1.1 1.2 1.3 1.7 1.8 1.8.1 1.9.0 1.9.1 2.0.0 2.0.1 2.0.2 2.1.0; do
  esc=$(printf '%s' "$v" | sed 's/\./\\./g')
  notes=$(awk "/^## \[$esc\] - /{f=1; next} /^## /{f=0} f" CHANGELOG.md)
  if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
    echo "ABORT: empty notes for $v"; break
  fi
  printf '%s\n' "$notes" | gh release create "v$v" --title "v$v" --notes-file - || break
done
```

The empty-notes guard matters: a release created with empty notes has to be
edited afterwards, and a silent `awk` miss would produce twelve of them.

- [ ] **Step 4: Verify the releases**

```bash
gh release list                       # 12 entries, v2.1.0 marked Latest
gh release view v2.0.0 --json body -q .body | head -20
diff <(gh release view v1.8.1 --json body -q .body) \
     <(awk '/^## \[1\.8\.1\] - /{f=1; next} /^## /{f=0} f' CHANGELOG.md)
```

Expected: twelve releases; the `diff` shows no content differences (trailing
whitespace only, if any).

- [ ] **Step 5: Close issue #2**

Replace `<merge-sha>` with the sha recorded in Task 5, Step 4.

```bash
gh issue close 2 --comment "Done in <merge-sha>. CHANGELOG.md reconstructed back to 1.1, twelve annotated tags (v1.1…v2.1.0) pushed with backdated tagger dates, twelve GitHub Releases created from the changelog sections, and the forward process documented in docs/RELEASE.md — enforced by ChangelogVersionTest and OpenApiVersionTest rather than by CI.

Two corrections to the issue as written: the 1.9.0 bump is c7c878f (not 2cf5dfb), and 2.1.0 postdated the issue and is included. Also, because version bumps in this repo opened each line of work rather than closing it, the 200/404 contract change lives under 1.9.1 (where it was implemented) with 2.0.0 recording the formal declaration, and the OpenAPI single-source work lands under 2.0.2."
```

- [ ] **Step 6: Final state check**

```bash
git tag -l | wc -l        # 12
gh release list | wc -l   # 12
gh issue view 2 --json state -q .state   # CLOSED
cd swapi-app && ./mvnw test              # BUILD SUCCESS
```

No deploy: `swapi-app/pom.xml` is still `2.1.0` and nothing in the artifact
changed.
