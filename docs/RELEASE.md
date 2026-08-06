# Release runbook

Canonical release procedure. A release is a version number, a changelog entry, a
tag, a GitHub Release and a deploy — in that order. Deploying without the first
four is how a version reaches production undocumented.

Release bookkeeping is deliberately manual — no Release Please, no version-bump
automation: the only failure mode that actually happens (bumping the version and
forgetting the changelog) is caught by `ChangelogVersionTest` in the normal test
run. The **deploy pipeline**, however, is automated since 2026-08-05: pushing a
release tag triggers `.github/workflows/deploy.yml` (suite → preview + probes →
manual approval → production). See `docs/DEPLOY.md`.

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
production. Pushing the tag (step 6) triggers the deploy workflow: suite →
preview deploy + runbook probes → **manual approval in the GitHub UI**
(environment `production`) → production deploy + post-deploy verification.
Approve at https://github.com/eldermoraes/swapi.build/actions after checking the
preview probe output. Manual fallback: follow `docs/DEPLOY.md` step by step.

## 9. Verify

```bash
curl -s https://swapi.build/openapi.json \
  | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1
```

Expect the version just released. The whitespace in the pattern is not optional:
the spec is served pretty-printed (`"version" : "2.1.0"`), so a `"version":"`
pattern matches nothing and reads as a failed check when nothing is wrong.

Then the post-deploy checks in `docs/DEPLOY.md`.

## 10. MCP Registry (registry.modelcontextprotocol.io)

The server is listed as `build.swapi/star-wars`, published from the repo-root
`server.json`. Republishing is part of every release:

1. Bump `version` in `server.json` together with the pom
   (`ServerJsonVersionTest` fails the suite if they drift).
2. After the production deploy is verified, publish:

   ```bash
   # login proves control of swapi.build via the apex DNS TXT record
   # (key: ~/.config/swapi.build/mcp-registry-ed25519.pem — local only, never in the repo)
   PRIVATE_KEY="$(openssl pkey -in ~/.config/swapi.build/mcp-registry-ed25519.pem -noout -text \
     | grep -A3 'priv:' | tail -n +2 | tr -d ' :\n')"
   mcp-publisher login dns --domain swapi.build --private-key "${PRIVATE_KEY}"
   mcp-publisher publish   # run from the repo root, next to server.json
   ```

3. Verify the new version is live:

   ```bash
   curl -s 'https://registry.modelcontextprotocol.io/v0/servers?search=build.swapi/star-wars' \
     | grep -o '"version":"[^"]*"'
   ```

Key rotation: generate a new key, replace (never add alongside) the apex TXT
record `v=MCPv1; k=ed25519; p=...` on Cloudflare — a stale record is tried
first and breaks login.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ChangelogVersionTest` fails with "no '## [x.y.z] - YYYY-MM-DD' section" | The pom was bumped without a changelog entry, or the date format is wrong (must be `YYYY-MM-DD`). |
| `ChangelogVersionTest` fails with "section is empty" | The section exists but has no category heading or no bullets. An intentionally empty release still needs a `### Changed` line saying so — see 2.0.1. |
| `ChangelogVersionTest` fails with "repo root with CHANGELOG.md not found" | The suite was run from outside the checkout. Run it from `swapi-app/`. The lookup stops at the checkout root on purpose, so a worktree never reads the parent repo's changelog. |
| `OpenApiVersionTest` fails | Someone set `quarkus.smallrye-openapi.info-version` in `application.properties`. Remove it: the version must be inherited from the pom. |
| `gh release create` fails with "release already exists" | The release was created earlier. Use `gh release edit vx.y.z --notes-file -` instead. |
| Released version not visible at `/openapi.json` | The tag exists but the deploy did not run — or it ran and is still waiting for the approval gate. Check the Deploy workflow in the Actions tab; a push of *commits* never deploys. See `docs/DEPLOY.md`. |
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
