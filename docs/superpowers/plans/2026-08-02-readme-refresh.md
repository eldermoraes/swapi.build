# README Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** README raiz fiel ao estado atual do projeto (estrutura com MCP e páginas novas, contratos 200/404, Bob path correto, cold start qualificado, licença explícita, contributing com testes) e `swapi-app/README.md` deixa de ser boilerplate enganoso.

**Architecture:** Docs-only. Nenhum código muda; verificação = greps + suíte intacta.

**Spec:** Pedido direto do Elder em 02/08/2026 ("Revise isso") após revisar o README. Auditoria feita pelo controller contra o código atual.

## Global Constraints

- Branch `docs/readme-refresh`. Nada além dos dois READMEs muda.
- A frase "never goes offline" (linha 5) NÃO muda (decisão anterior do usuário).
- Não precisa de deploy (README não é servido pelo site) — só merge + push.

---

### Task 1: Revisar README.md raiz + substituir swapi-app/README.md

**Files:**
- Modify: `README.md` (seções listadas abaixo)
- Rewrite: `swapi-app/README.md` (boilerplate → ponteiro)

- [ ] **Step 1: Seção API Endpoints — contratos atuais**

Após a tabela de endpoints e antes de "All responses are JSON. Example:", inserir:

```markdown
Ids are the record ids from each entity's `url` field (for films, `1` = A New Hope).
Successful responses return `200`; unknown or non-numeric ids return `404`.
```

- [ ] **Step 2: IBM Bob — caminho global correto**

Linha 123, de:

```
`~/.bob/mcp.json` (global) or `.bob/mcp.json` (project):
```

para:

```
`~/.bob/settings/mcp_settings.json` (global) or `.bob/mcp.json` (project):
```

- [ ] **Step 3: Cold start — alinhar à redação do site**

Linhas 140-141, de:

```
> The server scales to zero when idle — if the very first connection attempt fails, retry once
> (cold start is milliseconds; stateless requests are immune after that).
```

para:

```
> The server scales to zero when idle — if the very first connection attempt fails, retry once
> (the native binary starts in tens of milliseconds; the platform may take a bit longer to
> provision the container, and stateless requests are immune after that).
```

- [ ] **Step 4: Project Structure — árvore atual**

Substituir o bloco de árvore inteiro (linhas 145-169) por:

```
swapi-app/
  Dockerfile.vercel           # Native container image used by Vercel deploys
  src/main/
    java/com/eldermoraes/     # Backend (Quarkus + Jakarta REST)
      film/                   # Film model, service, resource
      people/                 # People model, service, resource
      planet/                 # Planet model, service, resource
      specie/                 # Specie model, service, resource
      starship/               # Starship model, service, resource
      vehicle/                # Vehicle model, service, resource
      mcp/                    # MCP server tools (sw_list, sw_get, sw_random, sw_search)
      SWObject.java           # Base model class
      SWService.java          # Service interface
      ApiResource.java        # Root /api endpoint
      ApplicationPath.java    # Jakarta REST base path (/api)
    resources/
      data/                   # Static JSON data files
      application.properties  # Quarkus configuration
    webui/                    # Frontend (TypeScript + Vite)
      src/
        api.ts                # API client with request management
        main.ts               # SPA router
        pages/                # Page renderers (home, resource, docs, mcp, about, privacy, terms)
        json-highlight.ts     # JSON syntax highlighting for result panels
        style.css             # Site styles
        types.ts              # TypeScript interfaces for API resources
        constants.ts          # Shared resource metadata
        utils.ts              # Shared utilities (escapeHtml)
  src/test/
    java/com/eldermoraes/     # Regression suite (REST contracts, MCP tools, forwarded headers)
```

- [ ] **Step 5: Tech Stack — linha do MCP**

Após a linha do Runtime (linha 202), inserir:

```markdown
- **MCP server:** [Quarkiverse MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html) — Streamable HTTP, stateless spec 2026-07-28
```

- [ ] **Step 6: Contributing — testes no fluxo**

Substituir o passo 3 da lista (linha 213), de:

```
3. Make your changes and verify: `cd swapi-app && ./mvnw quarkus:dev`
```

para:

```
3. Make your changes (with tests) and run the suite: `cd swapi-app && ./mvnw test`
```

- [ ] **Step 7: License — explícita + páginas legais**

Substituir a seção License (linhas 223-225) por:

```markdown
## License

Licensed under the [Apache License 2.0](LICENSE). The website also publishes a
[Privacy Policy](https://swapi.build/privacy) and [Terms of Use](https://swapi.build/terms).
```

- [ ] **Step 8: swapi-app/README.md — matar o boilerplate**

Substituir o conteúdo inteiro por:

```markdown
# swapi-app

This is the application module of [swapi.build](https://swapi.build). All documentation —
quick start, API endpoints, MCP server, build and deploy — lives in the
[repository root README](../README.md). Deploy procedure: [docs/DEPLOY.md](../docs/DEPLOY.md).
```

- [ ] **Step 9: Verificação**

- `grep -n "mcp.json (global)" README.md` → vazio; `grep -n "cold start is milliseconds" README.md` → vazio; `grep -n "8080" swapi-app/README.md` → vazio.
- `cd swapi-app && ./mvnw test` → 28/28 (nada de código mudou; sanidade).

- [ ] **Step 10: Commit**

```bash
git add README.md swapi-app/README.md
git commit -m "docs: README reflects current project — mcp package, legal pages, 200/404 contract, Bob path, Apache 2.0"
```
