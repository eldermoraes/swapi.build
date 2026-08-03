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
| `ChangelogVersionTest` fails with "repo root with CHANGELOG.md not found" | The suite was run from outside the checkout. Run it from `swapi-app/`. The lookup stops at the checkout root on purpose, so a worktree never reads the parent repo's changelog. |
| `OpenApiVersionTest` fails | Someone set `quarkus.smallrye-openapi.info-version` in `application.properties`. Remove it: the version must be inherited from the pom. |
| `gh release create` fails with "release already exists" | The release was created earlier. Use `gh release edit vx.y.z --notes-file -` instead. |
| Released version not visible at `/openapi.json` | The tag exists but the deploy did not run. Deploys are CLI-only — `git push` publishes commits and nothing else. See `docs/DEPLOY.md`. |
| The GitHub Release date is wrong on the retroactive releases | Expected. `gh release create` cannot backdate, so the twelve releases created on 2026-08-03 all carry that date; only the tags are dated correctly. |

## How the retroactive history was built

Versions 1.1 through 2.1.0 were tagged and released retroactively on 2026-08-03,
reconstructed from git history. Two properties of that history are worth knowing
before reading old entries:

- **Tags point at the last commit of each version line**, not at the version-bump
  commit, so `git checkout v2.0.0` gives the complete 2.0.0.
- **Bumps in this repo opened each line of work instead of closing it.** A change
  therefore belongs to the version whose commit range contains it, which is why
  the 200/404 contract change sits under 1.9.1 with 2.0.0 recording the formal
  declaration, and why the OpenAPI single-source work sits under 2.0.2.

Tag names are literal to the pom of their day, so five of them (`v1.1`, `v1.2`,
`v1.3`, `v1.7`, `v1.8`) are two-digit and not strict SemVer. There is no tag for
`1.0.0-SNAPSHOT`.
