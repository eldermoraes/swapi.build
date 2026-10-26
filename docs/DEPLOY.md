# Deploy runbook

Canonical deploy procedure. One host: the **self-hosted origin on Oracle
Cloud**, production since 2026-10-19. The native binary from the GitHub Release
runs under systemd behind Caddy. Everything about the host is in
[Self-hosted origin on Oracle Cloud](#self-hosted-origin-on-oracle-cloud).

Nothing of this project runs on Vercel any more: the Vercel deploy pipeline and
`Dockerfile.vercel` were removed on 2026-10-19, and there is no rollback to
Vercel. The root `vercel.json` stays, with `git.deploymentEnabled: false`,
only because the repository is still connected to the old Vercel project
through the Vercel GitHub app: without it, every push starts a Vercel
deployment (seen on PR #25). Once the repository is disconnected on the Vercel
side (project settings, Git), the file and `scripts/vercel-git-off.test.sh`
can go.

`scripts/verify-deploy.sh origin <host>` runs every probe below against the
host. CI and the operator use the same script.

## Automated deploy

Publishing a GitHub Release (`docs/RELEASE.md`, step 7) runs
`.github/workflows/native-binary.yml`:

1. **`test`**: the full suite on the tag (`./mvnw package`, which also builds the
   frontend).
2. **`build`**: the native binary (section 1 below), attached to the Release with
   its `application.sha256`; the hash also goes to the run summary and to the
   job's output.
3. **`deploy`**: waits for **manual approval** (environment `production`), then
   connects to the host over SSH as the `deploy` user and sends
   `update <tag> <sha256>`. On the host, the forced command `swapi-deploy-gate`
   checks that request and runs `sudo swapi-update <tag> <sha256>` (section
   2.5), which downloads the binary from the Release, checks the hash against
   both sources, switches `/opt/swapi/current`, restarts swapi and fails if it
   does not answer. Then `scripts/verify-deploy.sh origin swapi.build` and
   `scripts/verify-deploy.sh redirect www.swapi.build swapi.build` (`www`
   answers a 308 to the apex, see [The www redirect](#the-www-redirect)).

Pushing a tag or commits never deploys. The deploy is a job in the same
workflow (`needs: build`) rather than a separate `workflow_run`, so the hash
comes straight from the build job's output: no artifact download, and no
reading it back from the Release, which would reduce the two-source check of
`swapi-update` to one. If `deploy` fails after the binary is attached, fix the
cause and use **Re-run failed jobs** on the same run.

What the deploy needs, all outside the repository's files:

| Name | Kind | Holds |
|---|---|---|
| `ORACLE_DEPLOY_KEY` | Actions secret | Private half of an ed25519 key used only by this workflow. Its public half sits in `~deploy/.ssh/authorized_keys` on the host (section 2.11). |
| `ORACLE_KNOWN_HOSTS` | Actions secret | One `known_hosts` line, `<host IP> ssh-ed25519 AAAA...`, built from the host's `/etc/ssh/ssh_host_ed25519_key.pub`. The job connects with `StrictHostKeyChecking=yes`, so a changed host key stops the deploy. |
| `SWAPI_ORIGIN_IP` | Actions variable | The host's public IP (also read by the uptime monitor). |
| `production` | Environment | Required reviewer: the maintainer. Approval in the Actions UI or through the REST API (`docs/RELEASE.md`, step 8). |

**Redeploying a Release that already has its binary** (a reinstall, a
rollback through the pipeline, or a proof of the deploy path): run **Native
binary and deploy** from the Actions tab (or
`gh workflow run native-binary.yml -f tag=vX.Y.Z -f sha256=<hash>`) with the
hash copied from the summary of that release's original run. `test` and
`build` are skipped; `deploy` waits for approval and runs as above, with
`swapi-update` checking that hash against the Release's `application.sha256`.

The manual path is [Updating the version on the host](#updating-the-version-on-the-host).

## What the probes check

`scripts/verify-deploy.sh origin <host>`, run by the `deploy` job on
`swapi.build` and by hand after any change on the host. Every line must read
`PASS`. `www.swapi.build` is not probed as a site: it only redirects (see
[The www redirect](#the-www-redirect)).

- **REST**: `GET /api/people/1` returns 200 and embeds `https://<host>/api/...`
  URLs. Proves the per-request base-URL discovery through `X-Forwarded-*`.
- **Usage counters**: `GET /api/stats` returns 200 with `countingSince` and the
  `/api/people/{id}` route the REST probe just hit; `?period=24h` answers 24
  hourly buckets and `?period=30d` 30 daily ones (2.7.0+), each with a
  `performance` block holding all five groups and one latency point per bucket
  (2.8.0+), and `/stats` serves the page with its own title.
- **OpenAPI**: `/openapi.json` with `Accept: text/html` returns
  `200 application/json` (Quinoa must not swallow the route), and its
  `info.version` equals the pom of the checked-out tag: the deployed version
  is the released one.
- **SEO routes / social preview**: `robots.txt` (text/plain), `sitemap.xml`
  (XML), `og-image.png` (PNG) bypass the SPA fallback; `robots.txt` points at the
  sitemap on the host; the sitemap lists `/docs` and `/docs/webmcp`; the home
  page carries `canonical`, `og:url` and the Twitter Card with absolute URLs on
  the host; `/docs/webmcp` has its own title.
- **Cold start**: the time of one request, informational only.
- **MCP stateless**: a `tools/call` of `sw_get` with the 2026-07-28 headers and
  `_meta` returns `isError: false` and URLs on the host. `server/discover` and
  `tools/list` return flat `result.ttlMs: 0` and `result.cacheScope: "public"`,
  with no nested `result.cacheControl` (required by the 2026-07-28 protocol;
  CR1 omitted them).
- **MCP stateful burst**: twelve concurrent `tools/list` calls on one session
  must all return 200. Before `auto-init` this returned 33–58% `404` on Vercel's
  multiple instances. The script skips the burst and fails if `initialize`
  issued no session id: with `auto-init` on, an empty id also returns 200, so a
  failed extraction would otherwise look like a pass.
- **MCP foreign session and edges**: a session id that never existed is still
  served (200); `GET /mcp` is 405; `GET /mcp/sse` is 404 (legacy transport
  rejected); `POST /mcp/messages/*` is 404 with a JSON body citing `/mcp`.
- **CORS and `Vary: Origin`**: CORS echoes the request `Origin`, so cacheable
  responses (`/api/*` and the SPA HTML) must carry `Vary: Origin`, and a
  request without `Origin` gets no `Access-Control-Allow-Origin`.
- **Poisoning**: a spoofed `X-Forwarded-Host: evil.example` never reaches the
  REST body, the sitemap or the SPA HTML (Caddy overwrites the header), and an
  RFC 7239 `Forwarded` header is ignored by the app.
- **Caddy headers**: `Strict-Transport-Security: max-age=63072000` and no
  `Server` header.

### The www redirect

`www.swapi.build` answers every request with a **308** permanent redirect to
`https://swapi.build{uri}`, path and query preserved (decided 2026-10-26: one
address per page for search engines and answer engines). 308, not 301, so an
MCP client that posts to `www` is sent on to the apex still as a `POST` with
its body.

`scripts/verify-deploy.sh redirect www.swapi.build swapi.build`, run by the
`deploy` job right after the `origin` probes and by hand after any change to
the `Caddyfile`, sends `GET /`, `GET /api/people/1?format=json`,
`GET /sitemap.xml` and `POST /mcp` to `https://www.swapi.build` without
following redirects. Each must answer `308` with `Location` exactly
`https://swapi.build` + the same path and query. Every line must read `PASS`.
`scripts/verify-deploy-redirect.test.sh` (CI) runs the same mode against a
fake host on `127.0.0.1` and proves that a correct redirect passes and a wrong
status, a wrong host or a dropped query fails.

The redirect lives on the host, in the `Caddyfile` (section 4): the deploy
probe fails until the operator has applied it there.

## Troubleshooting (application)

| Symptom | Cause / fix |
|---|---|
| 404 `Mcp session not found` on a stateful MCP call | `quarkus.mcp.server.http.streamable.auto-init=true` is off in the running version. It serves an unknown session id with a throwaway session (introduced for Vercel's lack of session affinity; on the single host it keeps clients working across restarts). |
| 202 responses from the API | Legacy quirk retired 2026-08-01 — current builds return 200; a 202 means a very old binary is live. |
| The OpenAPI version probe fails right after a deploy | The host still runs the previous version: `swapi-update` failed, or the probe ran against a tag other than the deployed one. Read the `deploy` job log; on the host, `readlink /opt/swapi/current`. |

## WebMCP verification (2.5.0+)

Confirm `/docs/webmcp` returns HTML with title `WebMCP in the browser - SWAPI`
and appears in the sitemap. The automated probes check both. Browser tool
execution must also be verified with WebMCP enabled: discover `sw_list`,
`sw_get`, `sw_search`, `sw_random`; list PEOPLE, search Luke, open FILMS ID 1,
and show a random starship. Confirm the displayed record matches the returned
record and manual navigation supersedes pending work. Test the unsupported
browser path too. HTTP probes alone do not prove browser API compatibility.

The initial implementation was validated with Chrome 152, WebMCP testing
enabled, against the GraalVM native application. Chrome 152 can omit the
execution callback's cancellation options; the adapter accepts that version.
The browser API remains experimental, so record the tested browser version
when validating future changes.

To repeat browser verification against a running native binary, install the
frontend dev dependencies, use an installed compatible Google Chrome, and run:

```bash
cd swapi-app/src/main/webui
SWAPI_TEST_URL=http://127.0.0.1:5545 npm run test:browser
```

The script launches isolated Chrome sessions with WebMCP enabled and disabled;
it does not use your personal browser profile or a model API key. It covers
all six resource lists, the four tools, cancellation, history, manual search,
and a 360px viewport. It must run against a trusted test instance: the script
performs ordinary read-only queries. The local native instance needs to be
started on the corresponding port beforehand.

## Self-hosted origin on Oracle Cloud

Production since the DNS cutover of 2026-10-19. Everything needed to rebuild
the host from scratch is in this section, so a reinstall never depends on
anyone's memory.

**Shape.** One Always Free micro VM (x86_64, 1 GB RAM, Ubuntu 22.04, glibc
2.35) that already runs a Discord bot (`invite-role-bot.service`). The bot is
the tenant that must never be starved: every limit below exists to protect it.

```
internet ──► Caddy :80/:443 (TLS, Let's Encrypt) ──► swapi 127.0.0.1:5432 (native binary, systemd)
```

- swapi listens on loopback only; only Caddy talks to the internet.
- DNS stays on Cloudflare, **DNS-only (grey cloud)**, no proxy, no tunnel.
- Releases arrive through the `deploy` user (section 2.11), whose key can only
  run `swapi-update` with a validated tag and hash.

**Who does what.** Work on the host (SSH) is done by the maintainer, or by a
session he opens for that purpose. DNS changes need his explicit go, record by
record. Each step below says who owns it.

**Kept out of this public repository:** the host's public IP (the uptime
monitor and the deploy job read it from the repository variable
`SWAPI_ORIGIN_IP`), SSH keys (the deploy key lives only in the
`ORACLE_DEPLOY_KEY` secret), the Discord webhook, and where the bot's token
lives. Record those in the maintainer's private notes.

### Step 0: host lost, recreated, or account closed (any time)

**Before anything else, take the DNS away from the old IP.** An ephemeral
Oracle IP returns to the pool when its instance is terminated and may be handed
to another tenant, who could then obtain a valid Let's Encrypt certificate for
`swapi.build` (the records are DNS-only) and answer in its place, including on
`/mcp` for third-party agents.

Delete the `swapi.build` and `www.swapi.build` A records (maintainer's go),
then rebuild from this runbook on a new VM and point them at the new IP. A new
VM also has a new host key: update the `ORACLE_KNOWN_HOSTS` secret (section
2.11) and the `SWAPI_ORIGIN_IP` variable, or the next deploy stops at the host
key check. The uptime monitor's DNS check is what raises this alarm.

### 1. The binary: built by CI, attached to the Release

`.github/workflows/native-binary.yml` runs on `release: published` (after the
manual Release step of `docs/RELEASE.md`, so it never races the tag) and on
`workflow_dispatch` with an existing tag. After the `test` job, its `build`
job:

- builds with `-Dquarkus.native.container-build=true`, inside the UBI9
  Mandrel builder pinned in `quarkus.native.builder-image` (glibc 2.34). Building on the `ubuntu-latest` runner itself (glibc 2.39) would
  produce a binary that does not start on Ubuntu 22.04;
- passes `-Dquarkus.native.march=compatibility`, so the binary does not require
  AVX2/BMI2/FMA (the default `x86-64-v3` target), which the host CPU is not
  known to have;
- fails if `objdump -T` shows any `GLIBC_` symbol version above 2.35 (the
  regression test for the glibc trap);
- uploads `application` and `application.sha256` to the Release, and hands
  the hash to the `deploy` job (and prints it in the run summary): **a second
  source** for the checksum, so a binary swapped on the Release together with
  its `.sha256` is still caught by `swapi-update`.

A pull request that changes the workflow runs the suite, the build and the
GLIBC check without uploading or deploying anything.

### 2. Host preparation (one SSH window with write access)

**Before the window** (DNS, maintainer's go): create `oracle.swapi.build`, A →
host IP, DNS-only, TTL 300. The rehearsal certificate is then issued in the
same window.

**Owner of the window:** the maintainer, or a session he opens for it.

#### 2.1 Read-only checks first

1. Effective sshd settings (the drop-ins in `/etc/ssh/sshd_config.d/` win over
   `sshd_config`):
   `sudo sshd -T | grep -Ei 'passwordauthentication|kbdinteractive|permitrootlogin|allowusers'`
2. Where the bot token lives:
   `systemctl show invite-role-bot -p Environment -p EnvironmentFiles`, plus a
   search for the token's first characters under `/etc/systemd` and
   `/home/ubuntu`. Note **where** (private notes), never the value.
3. `stat -c '%a %U %n' /home/ubuntu <token file>`.
4. `sudo iptables -S INPUT` and `sudo cat /etc/iptables/rules.v4`: confirm
   the current rules reject everything except 22.
5. `systemctl cat invite-role-bot` and the content of its existing failure
   alert drop-in, to know how it alerts today.
6. `grep -m1 -o avx2 /proc/cpuinfo` (record only; `march=compatibility`
   already covers a CPU without it).

#### 2.2 SSH

Port 22 stays open to the internet, key-only, with fail2ban (already
installed).

1. Generate a new ed25519 key on the maintainer's machine, used for this host
   only. Append the public key to `~ubuntu/.ssh/authorized_keys`, **test a
   login with it**, and only then remove the old key.
2. If check 2.1.1 showed other values, set them in
   `/etc/ssh/sshd_config.d/10-hardening.conf`, then `sudo sshd -t` and
   `sudo systemctl restart ssh`:

   ```
   PasswordAuthentication no
   KbdInteractiveAuthentication no
   PermitRootLogin no
   AllowUsers ubuntu deploy
   ```

   `deploy` is the automated-deploy user of section 2.11. If `AllowUsers`
   names only `ubuntu`, sshd refuses it before the key is even checked.

#### 2.3 The bot token (touches the bot: maintainer's go on the spot)

`ProtectHome` only hides `/home`. A token in an `Environment=` line of the
unit file is readable by every local user, both from the file (mode 644) and
over D-Bus (`systemctl show`).

- If check 2.1.2 found it in `Environment=`: move it to an
  `EnvironmentFile=` owned by `ubuntu`, mode 600. `systemctl show` then shows
  the path, not the value. The bot restarts once.
- `sudo chmod 750 /home/ubuntu` and `chmod 600` on the token file.

#### 2.4 Failure alert (one service for every unit)

The webhook lives in a root-only file; no unit file holds it, and the
sandboxed services cannot read it.

`/usr/local/sbin/discord-alert` (owner root, mode 755):

```sh
#!/bin/sh
# usage: discord-alert <unit>   (run by discord-alert@.service)
set -eu
unit="$1"
webhook=$(cat /etc/discord-alert/webhook)
msg="$(hostname): ${unit} failed at $(date -u '+%Y-%m-%d %H:%M UTC')"
curl -fsS --max-time 10 -H 'Content-Type: application/json' \
  -d "{\"content\":\"${msg}\"}" "$webhook" >/dev/null
```

`/etc/systemd/system/discord-alert@.service`:

```ini
[Unit]
Description=Discord alert: %i failed

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/discord-alert %i
```

The webhook file, typed or pasted so it never lands in shell history:

```bash
sudo install -d -o root -g root -m 700 /etc/discord-alert
sudo sh -c 'umask 077; cat > /etc/discord-alert/webhook'   # paste the URL, Enter, Ctrl-D
```

Each monitored unit gets only `OnFailure=discord-alert@%n.service` in its
`[Unit]` section: the swapi unit below, the Caddy drop-in below, and, replacing
the bot's current alert drop-in (touches the bot: maintainer's go),
`/etc/systemd/system/invite-role-bot.service.d/alert.conf`:

```ini
[Unit]
OnFailure=discord-alert@%n.service
```

The message names the failed unit, so a swapi alert never reads as a bot
outage. Whether `OnFailure=` fires on every crash or only when systemd gives up
restarting depends on the systemd version: the rehearsal proves it (step 3.4).

#### 2.5 The `swapi` user and the binary

```bash
sudo useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin swapi
sudo install -d -o root -g root -m 755 /opt/swapi
```

`/usr/local/sbin/swapi-update` (owner root, mode 755). It is used for the first
install and for every later version:

```sh
#!/bin/sh
# usage: swapi-update <tag> <sha256 from the Actions run summary>
set -eu
tag="${1:?usage: swapi-update <tag> <sha256 from the run summary>}"
expected="${2:?usage: swapi-update <tag> <sha256 from the run summary>}"
case "$tag" in v[0-9]*.[0-9]*.[0-9]*) ;; *) echo "not a release tag: $tag"; exit 2 ;; esac
base="https://github.com/eldermoraes/swapi.build/releases/download/$tag"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --proto '=https' -o "$tmp/application" "$base/application"
curl -fsSL --proto '=https' -o "$tmp/application.sha256" "$base/application.sha256"
(cd "$tmp" && sha256sum -c application.sha256)                       # source 1: the Release
actual=$(sha256sum "$tmp/application" | cut -d' ' -f1)
[ "$actual" = "$expected" ] || { echo "hash differs from the run summary"; exit 1; }  # source 2

if ldd "$tmp/application" | grep -q 'not found'; then
  echo "missing shared libraries:"; ldd "$tmp/application"; exit 1
fi

install -d -o root -g root -m 755 "/opt/swapi/$tag"
install -o root -g root -m 755 "$tmp/application" "/opt/swapi/$tag/application"
previous=$(readlink -f /opt/swapi/current || true)
ln -sfn "/opt/swapi/$tag" /opt/swapi/current
systemctl restart swapi
sleep 3
if ! curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:5432/api/people/1; then
  echo "swapi $tag is not answering. Roll back with:"
  echo "  ln -sfn ${previous:-<previous version dir>} /opt/swapi/current && systemctl restart swapi"
  exit 1
fi
echo "swapi $tag is up"

# Keep only the active version and the previous one.
for dir in /opt/swapi/v*; do
  case "$dir" in "/opt/swapi/$tag" | "$previous") ;; *) rm -rf "$dir" ;; esac
done
```

Rolling back a version is `sudo ln -sfn /opt/swapi/<previous tag> /opt/swapi/current`
and `sudo systemctl restart swapi`.

#### 2.6 `swapi.service`

`/etc/systemd/system/swapi.service`:

```ini
[Unit]
Description=swapi.build (native)
After=network-online.target
Wants=network-online.target
OnFailure=discord-alert@%n.service

[Service]
Type=simple
User=swapi
Group=swapi
ExecStart=/opt/swapi/current/application -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=5432 -Xmx200m
Restart=always
# Without a delay a crash loop exhausts systemd's default start limit
# (5 starts in 10 s) in under a second and the unit gives up.
RestartSec=2

# Ceiling: under pressure swapi slows down or restarts; the bot is not touched.
MemoryHigh=250M
MemoryMax=300M
MemorySwapMax=0
CPUWeight=50

# No privileges, read-only disk.
NoNewPrivileges=yes
CapabilityBoundingSet=
AmbientCapabilities=
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes

# Away from the bot: unit files, the alert webhook, D-Bus (no AF_UNIX) and
# other processes are out of reach.
InaccessiblePaths=/etc/systemd/system /etc/discord-alert
RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK
ProtectProc=invisible
ProcSubset=pid

# No outbound network: answers Caddy on loopback, cannot reach the internet.
IPAddressDeny=any
IPAddressAllow=localhost

ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
LockPersonality=yes
RestrictNamespaces=yes
SystemCallFilter=@system-service

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo swapi-update <tag> <sha256 from the run summary>
sudo systemctl enable --now swapi
```

If a hardening directive keeps the native binary from starting, remove **only
that one** (`journalctl -u swapi` names it). Do not spend more than 10 minutes
on it. First suspect: `ProcSubset=pid`, which also hides `/proc/meminfo` and
`/proc/cpuinfo`.

**Cloudflare Web Analytics token (2.6.0+).** The page-visit beacon is injected
into the SPA HTML only when the app finds `CF_WEB_ANALYTICS_TOKEN` in its
environment at startup (`swapi.analytics.cloudflare-token`, read at runtime by
the native binary). The token comes from the Cloudflare dashboard, Web
Analytics, site `swapi.build`: it is the `token` value inside `data-cf-beacon`
in the JS snippet Cloudflare shows (the zone is DNS-only, so the snippet is the
only way in; paste only the token, not the snippet). It is not a secret: it
appears in every page. It lives in a drop-in, so `swapi-update` and edits to the unit leave it
alone, and turning it on or off needs a restart, not a release:

```bash
sudo install -d -m 755 /etc/systemd/system/swapi.service.d
sudo tee /etc/systemd/system/swapi.service.d/analytics.conf >/dev/null <<'EOF'
[Service]
Environment=CF_WEB_ANALYTICS_TOKEN=<token from the dashboard>
EOF
sudo systemctl daemon-reload
sudo systemctl restart swapi
journalctl -u swapi -n 20 | grep 'Web Analytics'   # "beacon enabled"
curl -s https://swapi.build/ | grep -c cloudflareinsights   # 1
```

A token that is not 16–64 letters and digits is refused with a warning in the
journal ("beacon disabled") instead of being written into the HTML. To turn the
beacon off, remove the drop-in, `daemon-reload` and restart. Neither Caddy nor
the app sends a `Content-Security-Policy` header today; if one is ever added, it
must allow `script-src https://static.cloudflareinsights.com` and
`connect-src https://cloudflareinsights.com`.

**Usage counters (2.6.0+, persisted from 2.7.0).** The app counts requests per
route template, per UTC hour and per UTC day (`com.eldermoraes.stats`): no IP, no
User-Agent, nothing about the client. Hours are kept 14 days and days 60 days,
which is what the windows of `GET /api/stats?period=24h|7d|30d` and the `/stats`
page need, each compared with the window before it; that is a few hundred KB at
most.

**Server times (2.8.0+).** The same filter times each counted request, from the
first route filter to the end of the response (server time; the network is not
in it), under the same route template. Per route and bucket it keeps a count,
the sum, the sum of squares, the minimum, the maximum and 84 fixed histogram
buckets (four per doubling from 0.05 ms to about 74 s, so p50/p95/p99 are within
about 9 %; see `LatencyHistogram`). One cell is about 0.5 KB; with all 45 routes
busy every retained hour and day that is under 9 MB, against `MemoryMax=300M`,
and in practice a small fraction of it. The restore line in the log reports the
live cell count and its size.

The counters live in memory and, when the unit has a state directory, are also
written to `/var/lib/swapi/route-stats.txt`: every 10 minutes if they changed,
and on shutdown, by writing a temporary file and renaming it over the old one.
At startup the app restores that file, so a deploy no longer resets the counters
(a crash loses at most 10 minutes). The file holds only route templates, bucket
starts, counts and the aggregated server times, the same things `/api/stats`
publishes. Its header names the format: `swapi-route-stats 2` from 2.8.0 (with
`th`/`td` time lines); a `swapi-route-stats 1` file from 2.7.0 still loads, with
its counts and no times. A corrupt or unreadable file is ignored with one
warning and replaced by the next snapshot.

`ProtectSystem=strict` keeps the rest of the disk read-only. The writable
directory comes from a drop-in with `StateDirectory=swapi`: systemd creates
`/var/lib/swapi` owned by `swapi`, mode 700, makes it writable inside the
sandbox, and passes the path to the app in `STATE_DIRECTORY`
(`swapi.stats.state-dir`). Nothing else in the unit changes, and `swapi-update`
leaves the drop-in alone. The operator script
`30-state-dir.sh` (kept outside the repository, run in an SSH window) does
exactly this, idempotently:

```bash
sudo install -d -m 755 /etc/systemd/system/swapi.service.d
sudo tee /etc/systemd/system/swapi.service.d/state.conf >/dev/null <<'EOF'
[Service]
StateDirectory=swapi
StateDirectoryMode=0700
EOF
sudo systemctl daemon-reload
sudo systemctl restart swapi
systemctl show swapi -p StateDirectory          # StateDirectory=swapi
sudo ls -ld /var/lib/swapi                      # drwx------ swapi swapi
journalctl -u swapi -n 30 | grep -i 'usage counters'
```

Without the drop-in the app works the same, in memory only, and logs once at
startup: `no state directory (STATE_DIRECTORY is not set): usage counters are
kept in memory only and restart from zero on every restart`. With it, that line
is absent; after the first restart that finds a snapshot the log says `usage
counters restored from /var/lib/swapi/route-stats.txt`. The restart that installs
the drop-in starts from zero (there is no snapshot yet), and the first snapshot
appears 10 minutes later or at the next stop:
`sudo ls -l /var/lib/swapi/`. To undo, remove `state.conf`, `daemon-reload`,
restart, and `sudo rm -rf /var/lib/swapi`.

The journal keeps a second, independent record: one
`route-stats <date> UTC total=… <route>=<count> …` line at 00:00 UTC for the day
that ended, and one marked `(partial, until shutdown)` when the app stops, so
the 30-day journal retention (2.9) keeps a month of daily totals even without
the state directory: `journalctl -u swapi --since -30d | grep route-stats`.

#### 2.7 Caddy

Install from the official repository:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install caddy
```

**Automatic updates.** unattended-upgrades ignores third-party repositories
unless told otherwise; without this Caddy never receives security fixes. Read
the repository's `o=` (origin) and `a=` (archive) from `apt-cache policy caddy`
and add them in `/etc/apt/apt.conf.d/51unattended-upgrades-caddy`:

```
Unattended-Upgrade::Origins-Pattern {
        "origin=<o>,archive=<a>";
};
```

Confirm with `sudo unattended-upgrade --dry-run --debug 2>&1 | grep -i caddy`.

**Drop-in** `/etc/systemd/system/caddy.service.d/hardening.conf`:

```ini
[Unit]
OnFailure=discord-alert@%n.service

[Service]
ProtectHome=yes
# The packaged unit is Type=notify, which needs an AF_UNIX socket, so D-Bus is
# closed by path instead of by address family.
InaccessiblePaths=/etc/systemd/system /etc/discord-alert /run/dbus/system_bus_socket
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
MemoryMax=200M
MemorySwapMax=0
CPUWeight=50
```

Caddy needs outbound network (Let's Encrypt), so it gets no `IPAddressDeny`.

**`/etc/caddy/Caddyfile`** (rehearsal form: only `oracle.swapi.build`):

```
{
	admin off
	servers {
		protocols h1 h2
		timeouts {
			read_header 10s
		}
	}
}

oracle.swapi.build {
	header -Server
	header Strict-Transport-Security "max-age=63072000"
	request_body {
		max_size 1MB
	}
	reverse_proxy 127.0.0.1:5432
}
```

- `admin off`: closes the admin API on `localhost:2019`, which swapi could
  otherwise reach.
- `protocols h1 h2`: no HTTP/3 advertised on UDP 443, which stays closed.
- Only a header read timeout: no `write` or `idle` timeout, which would cut the
  streamed responses of `/mcp`.
- HSTS: two years, the value the site has always sent, no `preload`, no
  `includeSubDomains`.
- `reverse_proxy` overwrites `X-Forwarded-*` coming from clients, which is what
  makes the app's `quarkus.http.proxy.allow-x-forwarded=true` safe here
  (`verify-deploy.sh origin` checks it).
- No access log: do not add a `log` directive except for a fixed, short
  investigation, and remove it afterwards.

**Rule: a name enters the `Caddyfile` only after its DNS already points at the
host.** Otherwise the ACME challenge lands on the old address, fails, and Let's Encrypt
rate-limits the name (5 failed validations per hour), which can delay the
certificate by hours on cutover day.

**Changing the `Caddyfile`:** `systemctl reload caddy` does **not** work with
`admin off` (reload goes through the admin API). Always:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl restart caddy
sudo journalctl -u caddy -f    # wait for "certificate obtained successfully" on a new name
```

The restart takes under a second; certificates stay on disk.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now caddy
sudo systemctl restart caddy
```

#### 2.8 iptables (ports 80 and 443)

Oracle's Ubuntu images reject everything but 22 in iptables. The new rules go
**before** the `REJECT` line (an `-A` after it has no effect).

Live, where `N` is the position of the `REJECT` line:

```bash
sudo iptables -L INPUT --line-numbers -n
sudo iptables -I INPUT N -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT N -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT
```

Persisted: edit `/etc/iptables/rules.v4` by hand and add, right before the
`-A INPUT -j REJECT ...` line:

```
-A INPUT -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT
-A INPUT -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT
```

Check with `sudo iptables-restore --test /etc/iptables/rules.v4`. **Never**
`netfilter-persistent save` (it would also save fail2ban's chain and its bans
of the moment) and never `ufw`. `rules.v6` only if the network gets IPv6
(there is no AAAA record today).

#### 2.9 Log retention

`/etc/systemd/journald.conf.d/retention.conf`, then
`sudo systemctl restart systemd-journald`:

```ini
[Journal]
MaxRetentionSec=30day
SystemMaxUse=500M
```

The privacy page states this 30-day limit.

#### 2.10 Oracle console (maintainer)

Security List of the VCN: ingress TCP 22, 80 and 443 only. Keep the ICMP rules
Oracle adds by default (type 3 code 4 is needed for path MTU discovery).

#### 2.11 The `deploy` user (automated deploys)

The `deploy` job of `native-binary.yml` logs in as `deploy` with the key in
the `ORACLE_DEPLOY_KEY` secret. That key can do one thing: ask for
`swapi-update <tag> <sha256>`.

- **The user:** a system account with a login shell (sshd runs the forced
  command through it) and no password (`*`, never a password login):

  ```bash
  sudo useradd --system --no-create-home --home-dir /home/deploy --shell /bin/bash --user-group deploy
  sudo usermod -p '*' deploy
  ```

- **`~deploy/.ssh/authorized_keys`**, a single line. Home, `.ssh` and the file
  are owned by root and only readable by the group `deploy` (750, 750, 640),
  so the user cannot rewrite its own forced command:

  ```
  restrict,command="/usr/local/sbin/swapi-deploy-gate" ssh-ed25519 AAAA... swapi-deploy-ci
  ```

  `restrict` turns off port, agent and X11 forwarding, the PTY and
  `~/.ssh/rc`; `command=` replaces whatever the client asks for with the gate.

- **`/usr/local/sbin/swapi-deploy-gate`** (owner root, mode 755): byte-for-byte
  `scripts/swapi-deploy-gate.sh` of this repository. It reads
  `SSH_ORIGINAL_COMMAND`, accepts only `update vX.Y.Z <64 lowercase hex>` as a
  whole string with single spaces, and runs
  `sudo -n /usr/local/sbin/swapi-update <tag> <sha256>` with the two values as
  separate arguments. Anything else (no command, a shell, scp, `;`, `$(`,
  extra spaces or arguments, a malformed tag or hash) is refused without running
  anything. `scripts/swapi-deploy-gate.test.sh` proves it in CI.

- **`/etc/sudoers.d/swapi-deploy`** (owner root, mode 440), checked with
  `visudo -cf` before it is installed:

  ```
  deploy ALL=(root) NOPASSWD: /usr/local/sbin/swapi-update
  ```

- **sshd:** if `AllowUsers` is set (section 2.2), it must include `deploy`.

- **`ORACLE_KNOWN_HOSTS`:** the host's ed25519 key in `known_hosts` form, read
  on the host itself, not with `ssh-keyscan` over the network:

  ```bash
  printf '%s %s\n' "<host IP>" "$(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
  ```

The maintainer's provisioning script (kept with his private notes) does all of
the above idempotently, run as root from standard input with the public key and
the host IP as arguments, and prints that line. It warns, without changing
anything, if sshd would refuse `deploy`. Its embedded gate can be checked
against this repository with
`scripts/swapi-deploy-gate.test.sh <provisioning script>`.

**Rotating the deploy key:** generate a new ed25519 key pair, replace the line
in `authorized_keys` (never add a second one), update `ORACLE_DEPLOY_KEY`, and
delete the old private key.

### 3. Rehearsal on `oracle.swapi.build` (same window)

1. `sudo journalctl -u caddy -f` until "certificate obtained successfully" for
   `oracle.swapi.build`.
2. `scripts/verify-deploy.sh origin oracle.swapi.build`: REST with `https://`
   URLs, OpenAPI, robots, sitemap, preview image, MCP stateless, MCP stateful
   burst, foreign session, edges, `Origin`/`Vary`, `X-Forwarded-Host` and
   `Forwarded` poisoning, HSTS, no `Server`.
3. `systemd-analyze security swapi` (record the score) and
   `journalctl -u swapi` (confirm MCP logs nothing about the client).
4. **Prove the alert:** `sudo systemctl kill -s KILL swapi` and confirm the
   message reaches Discord. Same for the bot (its alert was never tested).
5. **Reboot test**, with the maintainer around: `sudo reboot`. Afterwards:
   `systemctl status swapi caddy invite-role-bot fail2ban`,
   `sudo iptables -S INPUT`, and the probes of step 2 again. This covers the
   automatic 02:00 BRT reboot of unattended-upgrades, the persisted iptables
   rules and the enabled units in one go.
6. `free -m`: the bot keeps its usual memory.

### 4. Cutover (done on 2026-10-19)

`www.swapi.build` (19:22 BRT) and `swapi.build` (19:23 BRT) were switched to
A records pointing at the host, DNS-only, TTL 60, each name entering the
`Caddyfile` only after its DNS pointed at the host. Certificates were issued
and the probes went green on both names; `oracle.swapi.build` was then removed
from the `Caddyfile` and from the DNS. TTL goes back to 300 after 24 hours
without trouble (DNS, maintainer's go).

Site address in the `Caddyfile` at the cutover: `swapi.build, www.swapi.build {`,
with the `www` name served as-is (200, no redirect). Since the 2026-10-26
decision, `www.swapi.build` answers a 308 to `https://swapi.build{uri}`
instead (see [The www redirect](#the-www-redirect)): `swapi.build` keeps the
site block and `www.swapi.build` gets a block of its own that only redirects.
The host edit is the operator's;
`scripts/verify-deploy.sh redirect www.swapi.build swapi.build` confirms it.

For a future move to another host, the same order holds: TTL down to 60 the day
before (`swapi.build` sends HSTS for 2 years, so browsers will not let a
visitor click through a certificate error), then one name at a time: DNS →
wait 90 s → add the name to the `Caddyfile` → certificate →
`scripts/verify-deploy.sh origin <name>`. Expect 1 to 2 minutes of TLS errors
per name for whoever resolves the new address: the cost of having no tunnel.

**Uptime monitor.** `.github/workflows/uptime.yml` runs every 15 minutes. It
checks the API, more than 20 days left on both certificates (Caddy renews 30
days ahead), and that both names resolve only to `SWAPI_ORIGIN_IP`. GitHub
emails the owner on failure, and disables scheduled workflows after 60 days
without repository activity: re-enable it if so.

### 5. Rollback

The only way back is the previous version on the host. `swapi-update` keeps the
active version and the one before it under `/opt/swapi/`.

```bash
ls /opt/swapi/                                   # v<previous> and v<current>
sudo ln -sfn /opt/swapi/<previous tag> /opt/swapi/current
sudo systemctl restart swapi
curl -fsS https://swapi.build/openapi.json | grep -o '"version"[^,]*'
scripts/verify-deploy.sh origin swapi.build      # from a checkout of <previous tag>
```

`swapi-update` prints the exact `ln` command when a new version does not
answer. A rollback is a host change (maintainer, or a session he opens for it);
the next release then goes out through the normal pipeline. Rolling back to a
version older than the previous one: run `sudo swapi-update <tag> <sha256>` with
the hash from that release's run summary.

There is no DNS rollback to another provider: nothing of this project runs on
Vercel any more.

### Updating the version on the host

Normally done by the `deploy` job (see [Automated deploy](#automated-deploy)).
Manual path, when the job cannot run: open the **Native binary and deploy** run
of that release in the Actions tab, copy the hash from its summary, then on
the host:

```bash
sudo swapi-update <tag> <sha256 from the run summary>
curl -fsS https://swapi.build/openapi.json | grep -o '"version"[^,]*'
```

### Troubleshooting (self-hosted origin)

| Symptom | Cause / fix |
|---|---|
| `systemctl reload caddy` fails | Expected with `admin off`. Use `caddy validate` and `systemctl restart caddy`. |
| No certificate for a name; ACME errors in `journalctl -u caddy` | The name entered the `Caddyfile` before its DNS pointed at the host, or the DNS is still propagating. Check `dig @1.1.1.1 <name>`; if Let's Encrypt is rate-limiting, remove the name, wait an hour, add it back once DNS is right. |
| swapi does not start after an install | `journalctl -u swapi`. A hardening directive: remove only that one (first suspect `ProcSubset=pid`). A missing library: `ldd /opt/swapi/current/application`. `GLIBC_2.xx not found`: the binary was not built by `native-binary.yml`. |
| swapi restarts in a loop | `journalctl -u swapi`; an out-of-memory kill shows as `oom-kill` in `journalctl -k`. The ceilings are deliberate: they protect the bot. |
| The uptime monitor fails on DNS | Someone changed the records, or the VM was recreated: see [Step 0](#step-0-host-lost-recreated-or-account-closed-any-time). |
| The bot is slow or down | `free -m` and `systemctl status invite-role-bot`. If swapi or Caddy are the cause, their ceilings are too high: lower `MemoryMax`, never touch the bot first. |
| `deploy` job: `Host key verification failed` | The host key changed (VM recreated or SSH reinstalled) or `ORACLE_KNOWN_HOSTS` is wrong. Read the key on the host (section 2.11) and compare before updating the secret: an unexplained change is a reason to stop, not to overwrite. |
| `deploy` job: `Permission denied (publickey)` | `ORACLE_DEPLOY_KEY` does not match `~deploy/.ssh/authorized_keys`, or sshd's `AllowUsers` does not include `deploy` (`sudo sshd -T \| grep -i allowusers`). |
| `deploy` job: `swapi-deploy-gate: refused` | The workflow sent something other than `update vX.Y.Z <sha256>` (an empty hash from a skipped build, a non-release tag). Fix the workflow; never loosen the gate. |
| `deploy` job: `sudo: a password is required` | `/etc/sudoers.d/swapi-deploy` is missing or the `includedir` line is gone from `/etc/sudoers` (`sudo -l -U deploy`). |
