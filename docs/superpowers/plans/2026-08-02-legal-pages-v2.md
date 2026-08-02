# Legal Pages + v2.0.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bump para 2.0.0, páginas /privacy e /terms com links no footer, e os 5 minors de polish (clipboard, Home/End, aria-live, nota de troubleshooting, teste MCP FILMS null).

**Architecture:** Páginas novas seguem o padrão SPA existente: um `render<Page>(container)` em `pages/*.ts`, rota em `getRoute()`, título em `getPageTitle()`, case no switch de `navigate()` (main.ts), links no footer estático do `index.html`. Conteúdo legal é estático em inglês, tom simples. Polish é pontual em `mcp.ts` e um teste novo em `SwapiToolsTest`.

**Tech Stack:** TypeScript + Vite (sem test runner de frontend — `npm run build` + lint são a verificação), Quarkus/JUnit para o teste MCP.

**Spec:** `docs/superpowers/specs/2026-08-01-legal-pages-v2.md` (aprovada; contato = GitHub issues; polish incluído)

## Global Constraints

- Testes backend: `cd swapi-app && ./mvnw test` (porta 8081); nunca `mvn clean` com dev mode ativo.
- Frontend: `cd swapi-app/src/main/webui && npm run build && npm run lint` limpos.
- Branch `feat/legal-pages-v2`; implementadores Opus; validação pelo controller.
- Deploy fora do plano, único ao final, via `docs/DEPLOY.md`.
- Datas nas páginas legais: "Last updated: August 2, 2026".

---

### Task 0: Branch

- [ ] **Step 1:**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git checkout main && git checkout -b feat/legal-pages-v2
```

(Commitar spec+plano como primeiro commit.)

---

### Task 1: Versões 2.0.0

**Files:**
- Modify: `swapi-app/pom.xml:6` (`<version>1.9.1</version>` → `<version>2.0.0</version>`)
- Modify: `swapi-app/src/main/webui/package.json:4` (`"version": "1.7.0"` → `"version": "2.0.0"`)

- [ ] **Step 1:** Aplicar as duas trocas.
- [ ] **Step 2:** `cd swapi-app && ./mvnw test` → PASS (27/27); `cd src/main/webui && npm run build` limpo.
- [ ] **Step 3: Commit**

```bash
git add swapi-app/pom.xml swapi-app/src/main/webui/package.json
git commit -m "chore: version 2.0.0 — public contract changed (200, record ids, 404s)"
```

---

### Task 2: Páginas /privacy e /terms + footer

**Files:**
- Create: `swapi-app/src/main/webui/src/pages/privacy.ts` (export `renderPrivacy(container: HTMLElement): void`)
- Create: `swapi-app/src/main/webui/src/pages/terms.ts` (export `renderTerms(container: HTMLElement): void`)
- Modify: `swapi-app/src/main/webui/src/main.ts` (import, `getRoute`, `getPageTitle`, switch de `navigate()`)
- Modify: `swapi-app/src/main/webui/index.html` (footer)

**Interfaces:**
- Produces: rotas `/privacy` e `/terms` renderizadas pela SPA (e por load direto — Quinoa SPA routing já serve as rotas existentes assim).

- [ ] **Step 1: Criar as duas páginas**

Estrutura: espelhar `pages/about.ts` (mesmo shape de função, mesmas classes CSS de conteúdo textual — ler `about.ts` antes e reusar suas classes/wrappers; se `about.ts` usa uma classe container tipo `about-page`/`prose`, usar a mesma para herdar o estilo). Conteúdo EXATO (inglês) abaixo — títulos `<h1>`, seções `<h2>`, parágrafos/listas:

**privacy.ts — conteúdo:**

# Privacy Policy
*Last updated: August 2, 2026*

**The short version: swapi.build has no accounts, sets no cookies, runs no trackers, and stores nothing about you.**

## What we don't collect
The site and the API have no sign-up, no login, and no user profiles. The frontend sets no cookies and includes no analytics or tracking scripts of any kind. The application does not store any personal data.

## What gets processed technically
swapi.build is hosted on Vercel. Like virtually every hosting provider, Vercel keeps standard operational logs for requests (such as IP address, user agent, and request path) for infrastructure operation and abuse prevention. Those logs are handled and retained under Vercel's own policies; we don't enrich them, export them, or use them to identify anyone.

## The MCP server
The MCP endpoint at /mcp is stateless: each request is processed and answered, and its content is not stored. There are no sessions and no server-side state tied to you or your agent.

## Third parties
Hosting by Vercel; DNS by Cloudflare (DNS-only, no proxying). No data is sold or shared with anyone.

## Changes
If this policy changes, the date at the top changes with it.

## Contact
Questions? Open an issue on [GitHub](https://github.com/eldermoraes/swapi.build/issues).

*This is a plain-language statement written by the project maintainer, not legal advice.*

**terms.ts — conteúdo:**

# Terms of Use
*Last updated: August 2, 2026*

## The service
swapi.build is a free, open-source Star Wars API and MCP server, provided as is, with no warranty of any kind and no uptime guarantee. The service scales to zero when idle, so the very first request after a quiet period may be slower.

## Fair use
Use it for apps, demos, learning, and agents as much as you like. Don't abuse it: no flooding, scraping at hostile rates, or attempts to disrupt the service. Rate limiting may be applied if needed.

## The data
All data comes from community fan sources and is provided for fun and education. Accuracy is not guaranteed — canon disputes should be settled elsewhere.

## Star Wars
Star Wars and all associated names are trademarks of Lucasfilm Ltd. / Disney. This project is a fan work, not affiliated with, endorsed by, or connected to Lucasfilm or Disney in any way.

## The code
The source code is available on [GitHub](https://github.com/eldermoraes/swapi.build) under the Apache 2.0 license.

## Changes
These terms may change; the date at the top tells you when they last did.

## Contact
Questions? Open an issue on [GitHub](https://github.com/eldermoraes/swapi.build/issues).

*This is a plain-language statement written by the project maintainer, not legal advice.*

(Links em HTML: `<a href="..." target="_blank" rel="noopener">`.)

- [ ] **Step 2: Rotas em main.ts**

Em `getRoute()`, após a linha do `about`:

```ts
  if (parts[0] === 'privacy') return { page: 'privacy' };
  if (parts[0] === 'terms') return { page: 'terms' };
```

Em `getPageTitle()`:

```ts
    case 'privacy':
      return 'Privacy Policy - SWAPI';
    case 'terms':
      return 'Terms of Use - SWAPI';
```

No switch de `navigate()`:

```ts
    case 'privacy':
      renderPrivacy(container);
      break;
    case 'terms':
      renderTerms(container);
      break;
```

Com os imports correspondentes no topo. Conferir como os links internos são interceptados em `main.ts` (delegação de clique/popstate) e garantir que `/privacy` e `/terms` naveguem pelo mesmo mecanismo das demais rotas.

- [ ] **Step 3: Footer em index.html**

Dentro do `<footer class="footer">`, após o parágrafo `footer-github`:

```html
        <p class="footer-legal">
          <a href="/privacy">Privacy Policy</a> &middot;
          <a href="/terms">Terms of Use</a>
        </p>
```

- [ ] **Step 4:** `npm run build && npm run lint` limpos; abrir mentalmente o checklist: as duas rotas renderizam, títulos corretos, links do footer funcionam.
- [ ] **Step 5: Commit**

```bash
git add swapi-app/src/main/webui
git commit -m "feat: add Privacy Policy and Terms of Use pages"
```

---

### Task 3: Polish (5 minors)

**Files:**
- Modify: `swapi-app/src/main/webui/src/pages/mcp.ts` (clipboard, Home/End, aria-live, nota de troubleshooting)
- Test (modify): `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java`

- [ ] **Step 1: TDD do teste MCP — FILMS null path**

Adicionar a `SwapiToolsTest` (deve PASSAR de primeira — é cobertura de comportamento existente; rodar e confirmar):

```java
    @Test
    public void unknownFilmIdIsToolError() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "FILMS", "id", 9999))
                .withAssert(r -> assertTrue(r.isError()))
                .send()
                .thenAssertResults();
    }
```

Run: `cd swapi-app && ./mvnw test -Dtest=SwapiToolsTest` → PASS (se falhar, PARAR e reportar — seria bug real).

- [ ] **Step 2: Clipboard com .catch + aria-live**

Em `mcp.ts`, no template do `code()` (linha ~12), adicionar `aria-live="polite"` ao botão:

```html
      <button class="copy-btn" data-copy-target="${id}" aria-live="polite" aria-label="Copy to clipboard">Copy</button>
```

No handler (linhas ~210-219), tratar rejeição:

```ts
      navigator.clipboard.writeText(target.textContent ?? '').then(() => {
        btn.textContent = 'Copied!';
        setTimeout(() => (btn.textContent = 'Copy'), 1500);
      }).catch(() => {
        btn.textContent = 'Copy failed';
        setTimeout(() => (btn.textContent = 'Copy'), 1500);
      });
```

- [ ] **Step 3: Home/End nas abas**

No keydown das abas (linhas ~204-207), adicionar:

```ts
      if (e.key === 'Home') {
        e.preventDefault();
        selectTab(0);
      }
      if (e.key === 'End') {
        e.preventDefault();
        selectTab(tabs.length - 1);
      }
```

- [ ] **Step 4: Nota no troubleshooting**

Na seção `mcp-trouble`, adicionar após o parágrafo existente:

```html
      <p>Probing <code>/mcp</code> with raw <code>curl</code>? Stateless requests must include the
      <code>MCP-Protocol-Version</code>, <code>Mcp-Method</code> and <code>Mcp-Name</code> headers —
      MCP clients send these automatically.</p>
```

- [ ] **Step 5:** `npm run build && npm run lint` limpos; suíte backend completa PASS.
- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/webui/src/pages/mcp.ts swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java
git commit -m "polish: clipboard error handling, tabs Home/End, aria-live, curl note, FILMS error-path test"
```

---

### Task 4: Verificação final e handoff (controller)

- [ ] **Step 1:** Suíte completa (28 esperados) + `npm run build && npm run lint`.
- [ ] **Step 2:** Smoke em dev mode (5432): `/privacy` e `/terms` renderizam com título certo; footer linka; botão Copy da página MCP segue funcionando.
- [ ] **Step 3:** Decisão de merge com o usuário; suíte no merge.
- [ ] **Step 4:** Deploy via `docs/DEPLOY.md`; pós-deploy: páginas no ar em `https://swapi.build/privacy` e `/terms` (pré-requisito do Claude Connectors Directory cumprido).
