# Release runbook

Canonical release procedure. A release is a version number, a changelog entry, a
tag, a GitHub Release and a deploy — in that order. Deploying without the first
four is how a version reaches production undocumented.

Release bookkeeping is deliberately manual — no Release Please, no version-bump
automation: the only failure mode that actually happens (bumping the version and
forgetting the changelog) is caught by `ChangelogVersionTest` in the normal test
run. The **deploy pipeline**, however, is automated: publishing the GitHub
Release (step 7) triggers `.github/workflows/native-binary.yml` (suite → native
binary attached to the Release → manual approval → install on the self-hosted
origin → production probes). See `docs/DEPLOY.md`, "Automated deploy".

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
production. Publishing the Release (step 7) triggers **Native binary and
deploy** (`native-binary.yml`). Pushing the tag alone deploys nothing.

1. `test` runs the full suite on the tag; `build` builds the native binary,
   attaches `application` and `application.sha256` to the Release and prints
   the hash in the run summary.
2. `deploy` waits for **manual approval** (environment `production`). Approve
   in the Actions UI, or through the API:

   ```bash
   run=<run id of the Native binary and deploy run>
   env_id=$(gh api repos/eldermoraes/swapi.build/actions/runs/$run/pending_deployments --jq '.[0].environment.id')
   gh api -X POST repos/eldermoraes/swapi.build/actions/runs/$run/pending_deployments \
     -F "environment_ids[]=$env_id" -f state=approved -f comment="release vx.y.z"
   ```

3. After approval, the job connects to the host as `deploy`, runs
   `swapi-update <tag> <sha256>` through the gate and then
   `scripts/verify-deploy.sh origin swapi.build` and
   `scripts/verify-deploy.sh redirect www.swapi.build swapi.build` (`www`
   answers a 308 to the apex; `docs/DEPLOY.md`, "The www redirect").
   Every probe line must read PASS.

If the deploy job fails after the binary is attached (SSH, a probe), fix the
cause and use **Re-run failed jobs** on the same run: it reuses the hash from
`build`. Manual fallback: `docs/DEPLOY.md`, "Updating the version on the host".

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
`server.json`. The manifest names the server and its remote only: tools,
resources and prompts (2.13.0+) are not in `server.json`; clients read them
from the server itself. Republishing is part of every release:

1. Bump `version` in `server.json` together with the pom
   (`ServerJsonVersionTest` fails the suite if they drift).
2. Only after the `deploy` job read `PASS` on **every** probe line, including
   `favicon.svg` and `icon-512.png` (the two `icons` of `server.json`, 2.9.0+),
   publish. Publishing earlier points the registry at a path that may still
   answer the SPA shell (`text/html`):

   ```bash
   # login proves control of swapi.build via the apex DNS TXT record
   # (key: ~/.config/swapi.build/mcp-registry-ed25519.pem — local only, never in the repo)
   PRIVATE_KEY="$(scripts/registry-key.sh ~/.config/swapi.build/mcp-registry-ed25519.pem)"
   mcp-publisher login dns --domain swapi.build --private-key "${PRIVATE_KEY}"
   mcp-publisher publish   # run from the repo root, next to server.json
   ```

   `scripts/registry-key.sh` prints the raw key in hex (64 characters) only for
   the shell variable, and exits with an error on anything else. Do not inline an
   `openssl ... | grep` recipe instead: the old one kept the `pub:` label, gave 67
   characters and broke the login (2026-10-19). `scripts/registry-key.test.sh`
   covers it in CI.

3. Verify the new version is live — query the server's **versions** endpoint, not
   the search index:

   ```bash
   curl -s 'https://registry.modelcontextprotocol.io/v0/servers/build.swapi%2Fstar-wars/versions'
   ```

   Expect the version just published with `"isLatest": true`, and the previous one
   flipped to `false`. The `%2F` is required — the unescaped name 404s.

   Do **not** verify with `?search=`: its index lags a few minutes behind a publish,
   so it keeps returning the *previous* version and reads as a failed publish when
   nothing is wrong. Seen on 2026-08-10 with 2.2.1 (see Troubleshooting).

Key rotation: generate a new key, replace (never add alongside) the apex TXT
record `v=MCPv1; k=ed25519; p=...` on Cloudflare — a stale record is tried
first and breaks login.

## 11. Search engines

Only for a release that changes the set of indexable URLs (a new type of record
address marked `indexable` in `seo/site.json`, a new page, a URL that now
redirects) or what a crawler without JavaScript reads on an indexable URL
(2.11.0 drew the four fixed pages on the server; 2.12.0 does the same for the
record addresses), and only after every probe line read `PASS`:

1. IndexNow (Bing and the other participants; Google does not take part), with
   the new or changed URLs and the sitemap. The key is the one
   `swapi-app/src/main/webui/public/*.txt`, whose body is the key itself.
   Example from 2.10.0 (the six films):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.indexnow.org/indexnow \
     -H 'Content-Type: application/json; charset=utf-8' \
     -d '{"host":"swapi.build","key":"a5b77f3621994d6d59f6e5f3d85055a5","keyLocation":"https://swapi.build/a5b77f3621994d6d59f6e5f3d85055a5.txt","urlList":["https://swapi.build/resource/films/1","https://swapi.build/resource/films/2","https://swapi.build/resource/films/3","https://swapi.build/resource/films/4","https://swapi.build/resource/films/5","https://swapi.build/resource/films/6","https://swapi.build/sitemap.xml"]}'
   ```

   Example from 2.11.0 (the four pages whose content is now in the HTML):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.indexnow.org/indexnow \
     -H 'Content-Type: application/json; charset=utf-8' \
     -d '{"host":"swapi.build","key":"a5b77f3621994d6d59f6e5f3d85055a5","keyLocation":"https://swapi.build/a5b77f3621994d6d59f6e5f3d85055a5.txt","urlList":["https://swapi.build/","https://swapi.build/docs","https://swapi.build/docs/mcp","https://swapi.build/about","https://swapi.build/sitemap.xml"]}'
   ```

   Example from 2.12.0 (the record addresses now drawn on the server; only the
   six films are indexable, so only they go, with the sitemap):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.indexnow.org/indexnow \
     -H 'Content-Type: application/json; charset=utf-8' \
     -d '{"host":"swapi.build","key":"a5b77f3621994d6d59f6e5f3d85055a5","keyLocation":"https://swapi.build/a5b77f3621994d6d59f6e5f3d85055a5.txt","urlList":["https://swapi.build/resource/films/1","https://swapi.build/resource/films/2","https://swapi.build/resource/films/3","https://swapi.build/resource/films/4","https://swapi.build/resource/films/5","https://swapi.build/resource/films/6","https://swapi.build/sitemap.xml"]}'
   ```

   Expect `200` or `202`.
2. Search Console (maintainer): resubmit `sitemap.xml` and inspect one of the
   new URLs as the baseline for the coverage reading that gates the next batch.
   For a release that changed what a crawler reads, use "Test live URL" on one
   changed page (for 2.11.0, `/docs/mcp`; for 2.12.0, `/resource/films/1`) and check the text of `<main>` in the
   rendered HTML before requesting indexing.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ChangelogVersionTest` fails with "no '## [x.y.z] - YYYY-MM-DD' section" | The pom was bumped without a changelog entry, or the date format is wrong (must be `YYYY-MM-DD`). |
| `ChangelogVersionTest` fails with "section is empty" | The section exists but has no category heading or no bullets. An intentionally empty release still needs a `### Changed` line saying so — see 2.0.1. |
| `ChangelogVersionTest` fails with "repo root with CHANGELOG.md not found" | The suite was run from outside the checkout. Run it from `swapi-app/`. The lookup stops at the checkout root on purpose, so a worktree never reads the parent repo's changelog. |
| `OpenApiVersionTest` fails | Someone set `quarkus.smallrye-openapi.info-version` in `application.properties`. Remove it: the version must be inherited from the pom. |
| `gh release create` fails with "release already exists" | The release was created earlier. Use `gh release edit vx.y.z --notes-file -` instead. |
| Released version not visible at `/openapi.json` | The Release was not published (a tag alone deploys nothing), or the **Native binary and deploy** run is still waiting for the approval gate, or its `deploy` job failed. Check the run in the Actions tab. See `docs/DEPLOY.md`. |
| The GitHub Release date is wrong on the retroactive releases | Expected. `gh release create` cannot backdate, so the twelve releases created on 2026-08-03 all carry that date; only the tags are dated correctly. |
| `mcp-publisher publish` says "Successfully published", but the registry still shows the previous version | Almost certainly the `?search=` index, which lags a few minutes — it is not authoritative and the publish did work. Confirm with the `/versions` endpoint in step 10.3, which shows every version with its `isLatest`. Hit on 2026-08-10 publishing 2.2.1: `?search=` said 2.2.0 while `/versions` already had 2.2.1 as latest. |
| `mcp-publisher login dns` fails right after deriving the key | Check the key length: `echo "${#PRIVATE_KEY}"` must print 64. Derive it with `scripts/registry-key.sh`, which refuses anything else; the old `grep -A3 'priv:'` recipe produced 67 characters. |
| `/v0/servers/build.swapi/star-wars/versions` returns 404 | The server name must be URL-encoded in the path: `build.swapi%2Fstar-wars`. |

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
