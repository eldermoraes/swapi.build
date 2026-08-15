# swapi.build as an MCP Server — evaluation (blindspot)

**Date:** 2026-07-31
**Question:** does it make sense to also expose the API as an MCP Server, as a gateway for demos with LLMs?
**Short verdict:** yes — but for the sake of **distribution and positioning**, not the functional gain. And the timing is exceptionally good: the MCP spec has just stabilized on a model that favors exactly your stack, and the "demo data API with an official remote MCP" niche is vacant.

---

## Findings, by impact

### 1. The functional gain is modest; the real gain is distribution and positioning

Honest assessment of the ecosystem: for a simple, well-documented REST API without auth, an agent with a fetch tool already gets by — MCP does not add much *functionally* ([WorkOS: MCP vs REST](https://workos.com/blog/mcp-vs-rest)). What MCP really adds:

- **Distribution**: presence in the directories (official MCP Registry, PulseMCP, Glama, mcp.so, Smithery) and in the Claude Connectors Directory — one-click connection in Claude/ChatGPT/Cursor, including for users who don't code and have no fetch tool.
- **Vacant niche**: **no** small demo data API running a *first-party* remote MCP was found. Large vendors (GitHub, Stripe, Cloudflare, Context7) are already standard; the closest analogue (Open-Meteo) only has third-party wrappers. swapi.build would be early, not late.
- **Meta value — the strongest one for you**: SWAPI is already a proven MCP *teaching* topic (John Papa's repo is explicitly a didactic demo; the "Star Wars Copilot" series has an MCP lesson). swapi.build exists for "demos that never break" — an official remote MCP closes the loop: the API becomes the very subject of "how to build/consume MCP" talks. That connects directly with your work as a speaker/content creator.

### 2. Competition: four wrappers, all weak — the space is yours

Verified via the GitHub API on 2026-07-31:

| Repo | Stars | Type | Backend |
|---|---|---|---|
| johnpapa/mcp-starwars | 10 | local stdio, didactic demo | swapi.dev (stalled since Apr 2025) |
| glaucia86/swapi-mcp-server-app | 15 | local, "study purposes" | SWAPI |
| pipeworx-io/mcp-swapi | 0 | part of a gateway | swapi.dev |
| vitormm/mcp-starwars | 0 | local | SWAPI |

All local (stdio), none hosted, none by the API's owner, all on top of the unstable swapi.dev — exactly the fragility that motivated swapi.build. An official remote MCP at `swapi.build/mcp` dominates this niche on day 1.

### 3. Timing: the spec changed 3 days ago — building on the right model now avoids rework

- Spec **2026-07-28** (published on 2026-07-28): the biggest revision in the protocol's history — **stateless Streamable HTTP** (no handshake, no session id, each request self-contained). The legacy HTTP+SSE transport is deprecated with shutdown in 12 months.
- Mature governance: MCP was donated to the Agentic AI Foundation (Linux Foundation) in Dec 2025; adopted by OpenAI (ChatGPT's Apps SDK is *built on* MCP), Google and Microsoft. It is not a bet on a single vendor's protocol.
- Real counterpoint: there is a 2026 backlash for *coding agents* (CLI + Skills pattern, token-bloat criticism). The emerging consensus is hybrid: Skills/CLI for the dev workflow, **MCP for hosted/consumer connectivity — exactly swapi.build's case**.
- Derived rule: **build directly on the stateless model, never on legacy SSE or stateful patterns.**

### 4. Implementation: half a day of work, zero infra change (Option A)

**Recommendation: `io.quarkiverse.mcp:quarkus-mcp-server-http` 1.13.1** (stable, 2026-07-02) in the same app.

- Tools declared with `@Tool`/`@ToolArg` over the services that already have everything in memory; JSON schema generated at build time.
- The `/mcp` endpoint (Streamable HTTP) coexists with the Jakarta REST `/api/*` on the same HTTP server — same container, same Vercel deploy, same domain: **`https://swapi.build/mcp`**.
- Native image supported by the extension (a smoke test on the `Dockerfile.vercel` Mandrel/UBI9 build is still missing).
- Active extension: 3 releases in 6 weeks; the **2.0.0 (beta)** line already targets the 2026-07-28 spec — ship on 1.13.x now, bump to 2.0 GA when it lands.
- The Vercel container cold start (scale-to-zero) is irrelevant: the native image boots in milliseconds and the stateless calls are short POSTs.

**Discarded:**
- *Option B — Cloudflare Worker with `createMcpHandler()`* (Agents SDK v0.20.0): viable and aligned with the new spec, but creates a second codebase/toolchain/deploy and duplicates schemas by hand. Kept as a fallback if the MCP layer ever needs isolating. (Note: `McpAgent` became a legacy path — do not use.)
- *Option C — Vercel's `mcp-handler`*: it's for Next.js/Node routes; does not apply to a custom container deploy.

**Tool design:** prefer a few generic tools (~4: `list`, `get`, `random`, `search` with a `resource` parameter as a 6-value enum) over 24 specific ones — the 2026 token-bloat criticism punishes servers that inflate the agent's context.

### 5. No auth is fine; the operational risk is traffic, and the mitigation is already in front of you

- The spec says explicitly: "Authorization is OPTIONAL". An authless remote server for read-only public data is a first-class pattern (Cloudflare's official template is authless).
- The real risk of a public MCP server without auth = abuse/traffic, not leakage (MCP security incidents are about private data/writes — they don't apply). Recommended mitigation (OWASP): **rate limiting at the edge** — Cloudflare is already in front of the domain; it's a matter of creating a rule for `/mcp*`.

### 6. Distribution has a cheap checklist, but with gotchas

For the **Claude Connectors Directory** (largest consumer distribution):
- **Public privacy policy — without it, immediate rejection** (even collecting nothing);
- Correct `readOnlyHint` on **all** tools;
- Public docs page + **3+ example prompts** + testing instructions for the reviewer;
- "No authentication" is an explicitly supported category.

Beyond that: register in the **official MCP Registry** (namespace verified by DNS — swapi.build already covers that) and in the community directories (PulseMCP, Glama, mcp.so, Smithery). The 2026 roadmap brings discoverability via `.well-known` — worth implementing early once it stabilizes.

### 7. Real maintenance cost: spec churn — absorbed by the extension

Three relevant transport/auth revisions in ~20 months. For whoever maintains an MCP server by hand, that is a recurring cost; for whoever uses the Quarkiverse extension, the churn is absorbed by a dependency upgrade. That is the decisive argument for Option A.

### 8. Future bonus: MCP Apps

The 2026-07-28 spec formalized **MCP Apps** (server-rendered UI inside the chat, cross-vendor Claude/ChatGPT standard). It opens the "Star Wars character card inside Claude" demo — second-generation talk material, not needed in v1.

---

## Action plan (impact × effort)

| # | Action | Effort | Impact |
|---|---|---|---|
| 1 | Spike: extension 1.13.1 + ~4 generic `@Tool` tools, validate in `quarkus:dev` and in the Mandrel native build | half a day | High — unblocks everything |
| 2 | Deploy on the existing container + `/mcp*` rate limit rule on Cloudflare | 1-2h | High |
| 3 | MCP docs page on the site + privacy policy + example prompts | 2-3h | High — distribution prerequisite |
| 4 | Register: official MCP Registry (DNS), PulseMCP, Glama, mcp.so, Smithery; submit to the Claude Connectors Directory | 2-3h | High — where the gain lives |
| 5 | Content: talk/article "from the API that never goes down to the official MCP server" | ongoing | Medium-high — meta value |
| 6 | Bump to extension 2.0 GA (stateless spec) when it lands; `.well-known` discovery | future | Medium |
| 7 | MCP Apps (visual card in the chat) | future | Medium — v2 demo material |

## Points to verify in the spike (not confirmed by the research)

1. `/mcp` + `/api/*` coexistence (inferred from the Quarkus architecture; confirm in dev mode — minutes).
2. Native build of the extension on the `Dockerfile.vercel` Mandrel/UBI9.
3. Which spec revision 1.13.x implements (any of them works with current clients).

## Reusable brief (how to instruct Claude about this topic from now on)

> swapi.build: read-only Star Wars API, Quarkus 3.23 + GraalVM native (Mandrel/UBI9), static in-memory data, single container on Vercel behind Cloudflare. MCP server: `quarkus-mcp-server-http` extension (Quarkiverse) in the same app, endpoint `https://swapi.build/mcp`, **stateless** Streamable HTTP (2026-07-28 spec), **no auth**, rate limiting on Cloudflare. Never: legacy SSE, stateful session patterns, Cloudflare's `McpAgent` (legacy), a second codebase for the MCP. Tools: few and generic (resource as an enum), all with `readOnlyHint`. Distribution: official MCP Registry + directories + Claude Connectors Directory (requires a public privacy policy).
