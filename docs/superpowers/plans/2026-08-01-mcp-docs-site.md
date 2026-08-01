# MCP Docs (README + Site) + MCP Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Documentar o MCP server no README e no site (página `/docs/mcp` com abas por client), enfatizando a spec stateless 2026-07-28, e aplicar o commit "MCP polish" do backlog — num único ciclo de deploy.

**Architecture:** Nova página SPA `pages/mcp.ts` (vanilla TS, padrão das páginas existentes) roteada em `/docs/mcp` (o path `/mcp` pertence ao endpoint MCP — GET retorna 405), com abas acessíveis para 5 guias de client; callout no hero da home; nav ganha "MCP". README ganha a versão compacta com `<details>`. No backend, só o polish: `destructiveHint = false` nas 4 tools + asserts + `%test` override.

**Tech Stack:** TypeScript/Vite (Quinoa), Quarkus 3.33.3, quarkus-mcp-server-http 2.0.0.Beta3, McpAssured.

## Global Constraints

- Endpoint MCP público **não muda**: `https://swapi.build/mcp` (POST/Streamable HTTP). A página do site vive em **`/docs/mcp`**.
- Snippets dos clients: usar EXATAMENTE os blocos definidos nas tasks (verificados em docs oficiais em 01/08/2026). Não inventar sintaxe.
- Copy do site/README em inglês, tom do site atual.
- CSS: usar as variáveis existentes (`--bg-secondary`, `--border`, `--accent`, `--radius`, `--font-mono` etc.); classes novas só as definidas na Task 2.
- Java: TDD; quirk 202 do REST intocado; todas as tools continuam `readOnlyHint = true`, `openWorldHint = false`.
- Versão: bump para **1.9.1** (pom + `quarkus.container-image.tag`) na task de deploy.
- Nunca imprimir o token Vercel. Não fazer `git push` (integração via controller).
- Builds de container locais: usar `podman` (`export PATH=/opt/podman/bin:$PATH`) — não existe `docker` nesta máquina.

---

### Task 1: MCP polish (destructiveHint + asserts + %test override) — TDD

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java` (4 anotações)
- Modify: `swapi-app/src/main/resources/application.properties`
- Test: `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java`

**Interfaces:**
- Consumes: tools `sw_list`/`sw_get`/`sw_random`/`sw_search` existentes; `McpAssured` (client estático `client()` já existente em `SwapiToolsTest`).
- Produces: metadados das tools com `destructiveHint=false`; propriedade `%test.swapi.public-base-url`. Nenhuma task posterior depende de assinaturas novas.

- [ ] **Step 1: Substituir o teste `toolsAreListedAsReadOnly` por um que cubra os hints das 4 tools**

Em `SwapiToolsTest.java`, substituir o método `toolsAreListedAsReadOnly` por:

```java
    @Test
    public void allToolsAdvertiseReadOnlyNonDestructiveHints() {
        client().when()
                .toolsList(page -> {
                    assertEquals(4, page.size());
                    for (String name : java.util.List.of("sw_list", "sw_get", "sw_random", "sw_search")) {
                        var tool = page.findByName(name);
                        assertNotNull(tool, name + " ausente");
                        tool.annotations().ifPresentOrElse(a -> {
                            assertTrue(a.readOnlyHint(), name + " deveria ser readOnly");
                            assertFalse(a.destructiveHint(), name + " nao deveria anunciar destructive");
                            assertFalse(a.openWorldHint(), name + " nao deveria anunciar openWorld");
                        }, () -> fail(name + " sem annotations"));
                    }
                })
                .thenAssertResults();
    }
```

⚠️ A API fluente (`findByName`/`annotations()`/nomes dos accessors) já compilou nesse arquivo na versão atual; se algum accessor divergir (ex.: `destructiveHint()` retornar `Optional`), ajustar mantendo a intenção: as 4 tools com readOnly=true, destructive=false, openWorld=false.

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd swapi-app && ./mvnw test -Dtest=SwapiToolsTest#allToolsAdvertiseReadOnlyNonDestructiveHints`
Expected: FAIL — `destructiveHint` hoje é `true` (default da extensão não sobrescrito).

- [ ] **Step 3: Adicionar `destructiveHint = false` nas 4 anotações**

Em `SwapiTools.java`, em CADA uma das 4 `@Tool.Annotations(...)`, acrescentar `destructiveHint = false`. Exemplo (sw_list):

```java
          annotations = @Tool.Annotations(title = "List Star Wars resources",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
```

(`sw_get` e `sw_search` idem com `idempotentHint = true`; `sw_random` sem `idempotentHint`, apenas `readOnlyHint = true, destructiveHint = false, openWorldHint = false`.)

- [ ] **Step 4: Adicionar o `%test` override**

Em `application.properties`, junto das outras linhas `swapi.`:

```properties
%test.swapi.public-base-url=http://localhost:8081/api
```

- [ ] **Step 5: Suíte completa verde**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS (8 testes — o substituído continua contando 1).

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java \
        swapi-app/src/main/resources/application.properties \
        swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java
git commit -m "Polish MCP tool hints: destructiveHint=false, full hint asserts, %test base-url"
```

---

### Task 2: Página `/docs/mcp` com abas + nav + callout na home + CSS

**Files:**
- Create: `swapi-app/src/main/webui/src/pages/mcp.ts`
- Modify: `swapi-app/src/main/webui/src/main.ts` (rota `/docs/mcp`, título, nav ativo)
- Modify: `swapi-app/src/main/webui/index.html` (link "MCP" no nav)
- Modify: `swapi-app/src/main/webui/src/pages/home.ts` (callout no hero)
- Modify: `swapi-app/src/main/webui/src/style.css` (classes novas no fim)

**Interfaces:**
- Consumes: classes/variáveis CSS existentes (o escaping de HTML dos snippets é local à função `code()`).
- Produces: `export function renderMcp(container: HTMLElement): void` em `pages/mcp.ts`; rota `{ page: 'mcp' }`; classes CSS `.mcp-callout`, `.spec-callout`, `.code-block`, `.copy-btn`, `.tabs`, `.tab-btn`, `.tab-panel`, `.tools-table`, `.prompt-card`.

- [ ] **Step 1: Criar `pages/mcp.ts`**

```typescript
const ENDPOINT = 'https://swapi.build/mcp';

interface ClientGuide {
  id: string;
  label: string;
  html: string;
}

function code(id: string, lang: string, content: string): string {
  return `
    <div class="code-block" data-copy-id="${id}">
      <button class="copy-btn" data-copy-target="${id}" aria-label="Copy to clipboard">Copy</button>
      <pre id="${id}" class="code-pre ${lang}">${content
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')}</pre>
    </div>`;
}

const GUIDES: ClientGuide[] = [
  {
    id: 'claude-code',
    label: 'Claude Code',
    html: `
      <p>One command, from any terminal:</p>
      ${code('cc-add', 'bash', `claude mcp add --transport http swapi-build ${ENDPOINT}`)}
      <p>Or share it with your team via a <code>.mcp.json</code> at the repo root:</p>
      ${code('cc-json', 'json', `{
  "mcpServers": {
    "swapi-build": {
      "type": "http",
      "url": "${ENDPOINT}"
    }
  }
}`)}
      <p class="guide-verify"><strong>Verify:</strong> <code>claude mcp list</code> shows <code>swapi-build ✔ Connected</code>, or type <code>/mcp</code> inside a session.</p>`,
  },
  {
    id: 'claude-desktop',
    label: 'Claude Desktop & claude.ai',
    html: `
      <ol class="guide-steps">
        <li>Open <strong>Settings &rarr; Connectors</strong> and click <strong>Add custom connector</strong>.</li>
        <li>Name it <code>swapi-build</code> and paste the URL: <code>${ENDPOINT}</code>.</li>
        <li>Save — no authentication is required.</li>
      </ol>
      <p class="guide-verify"><strong>Verify:</strong> in any chat, the <strong>+</strong> menu &rarr; Connectors lists swapi-build with its tools.</p>
      <p class="guide-note">Available on all plans (Free allows one custom connector). On Team/Enterprise an Owner adds it under Organization settings &rarr; Connectors.</p>`,
  },
  {
    id: 'codex',
    label: 'OpenAI Codex',
    html: `
      <p>Streamable HTTP is supported natively — no gateway needed:</p>
      ${code('cx-add', 'bash', `codex mcp add swapi-build --url ${ENDPOINT}`)}
      <p>Or add it to <code>~/.codex/config.toml</code> (shared by the CLI, IDE extension and ChatGPT desktop app):</p>
      ${code('cx-toml', 'toml', `[mcp_servers.swapi-build]
url = "${ENDPOINT}"`)}
      <p class="guide-verify"><strong>Verify:</strong> <code>codex mcp list</code>, or <code>/mcp</code> inside the TUI.</p>`,
  },
  {
    id: 'copilot',
    label: 'GitHub Copilot',
    html: `
      <p>In VS Code, create <code>.vscode/mcp.json</code> in your workspace (note the top-level key is <code>servers</code>):</p>
      ${code('cp-json', 'json', `{
  "servers": {
    "swapi-build": {
      "type": "http",
      "url": "${ENDPOINT}"
    }
  }
}`)}
      <p>Or use the Command Palette: <strong>MCP: Add Server</strong> &rarr; HTTP &rarr; paste the URL.</p>
      <p class="guide-verify"><strong>Verify:</strong> open Copilot Chat and click the <strong>Configure Tools</strong> button — the four swapi tools appear under swapi-build.</p>
      <p class="guide-note">On Copilot Business/Enterprise, an admin must enable the "MCP servers in Copilot" policy. Visual Studio and JetBrains also support remote HTTP servers via their own <code>mcp.json</code>.</p>`,
  },
  {
    id: 'ibm-bob',
    label: 'IBM Bob',
    html: `
      <p>Add to <code>~/.bob/mcp.json</code> (global) or <code>.bob/mcp.json</code> in your project:</p>
      ${code('bob-json', 'json', `{
  "mcpServers": {
    "swapi-build": {
      "type": "streamable-http",
      "url": "${ENDPOINT}",
      "disabled": false
    }
  }
}`)}
      <p>Or from the Bob panel: <strong>MCP tab &rarr; Edit Global MCP</strong> (or Edit Project MCP).</p>
      <p class="guide-verify"><strong>Verify:</strong> Bob detects the tools automatically — expand swapi-build in the MCP tab to see them.</p>`,
  },
];

const PROMPTS = [
  'Using swapi-build, list every film Luke Skywalker appears in.',
  'Pick a random starship from swapi-build and compare its specs with the Millennium Falcon.',
  'Search swapi-build for planets matching "Tatooine" and summarize their climate and population.',
];

export function renderMcp(container: HTMLElement): void {
  container.innerHTML = `
    <section class="hero">
      <h1>MCP Server</h1>
      <p class="subtitle">Use the Star Wars API from your AI agent.</p>
    </section>

    <section class="mcp-endpoint">
      <h2>Endpoint</h2>
      ${code('endpoint', 'plain', ENDPOINT)}
      <div class="spec-callout">
        <strong>Built on the stateless MCP spec (2026-07-28).</strong>
        Every request is self-contained — no <code>initialize</code> handshake, no session ids,
        nothing to keep alive between calls. That makes it a natural fit for serverless clients
        and for live demos that must never break. First-party, read-only, no authentication.
      </div>
    </section>

    <section class="mcp-tools">
      <h2>Tools</h2>
      <table class="tools-table">
        <thead><tr><th>Tool</th><th>Arguments</th><th>Returns</th></tr></thead>
        <tbody>
          <tr><td><code>sw_list</code></td><td><code>resource</code></td><td>All entities of a resource</td></tr>
          <tr><td><code>sw_get</code></td><td><code>resource</code>, <code>id</code></td><td>One entity by id</td></tr>
          <tr><td><code>sw_random</code></td><td><code>resource</code></td><td>A random entity</td></tr>
          <tr><td><code>sw_search</code></td><td><code>resource</code>, <code>query</code></td><td>Name/title substring match</td></tr>
        </tbody>
      </table>
      <p class="guide-note"><code>resource</code> is one of <code>PEOPLE</code>, <code>FILMS</code>, <code>PLANETS</code>,
      <code>SPECIES</code>, <code>STARSHIPS</code>, <code>VEHICLES</code>. For <code>FILMS</code>, ids are
      episode ids (<code>4</code> = A New Hope).</p>
    </section>

    <section class="mcp-setup">
      <h2>Connect your client</h2>
      <div class="tabs" role="tablist" aria-label="MCP client setup">
        ${GUIDES.map(
          (g, i) => `
          <button class="tab-btn${i === 0 ? ' active' : ''}" id="tab-${g.id}" role="tab"
                  aria-selected="${i === 0}" aria-controls="panel-${g.id}" tabindex="${i === 0 ? 0 : -1}">
            ${g.label}
          </button>`,
        ).join('')}
      </div>
      ${GUIDES.map(
        (g, i) => `
        <div class="tab-panel" id="panel-${g.id}" role="tabpanel" aria-labelledby="tab-${g.id}"
             ${i === 0 ? '' : 'hidden'}>
          ${g.html}
        </div>`,
      ).join('')}
    </section>

    <section class="mcp-prompts">
      <h2>Try these prompts</h2>
      ${PROMPTS.map((p) => `<div class="prompt-card">${p}</div>`).join('')}
    </section>

    <section class="mcp-trouble">
      <h2>Troubleshooting</h2>
      <p>The server scales to zero when idle. If the very first connection attempt fails or times out,
      retry once — the container cold-starts in milliseconds and stateless requests are immune after that.</p>
    </section>
  `;

  const tabs = Array.from(container.querySelectorAll<HTMLButtonElement>('.tab-btn'));
  const panels = Array.from(container.querySelectorAll<HTMLElement>('.tab-panel'));

  function selectTab(idx: number): void {
    tabs.forEach((t, i) => {
      t.classList.toggle('active', i === idx);
      t.setAttribute('aria-selected', String(i === idx));
      t.tabIndex = i === idx ? 0 : -1;
    });
    panels.forEach((p, i) => {
      if (i === idx) p.removeAttribute('hidden');
      else p.setAttribute('hidden', '');
    });
    tabs[idx].focus();
  }

  tabs.forEach((tab, idx) => {
    tab.addEventListener('click', () => selectTab(idx));
    tab.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight') selectTab((idx + 1) % tabs.length);
      if (e.key === 'ArrowLeft') selectTab((idx - 1 + tabs.length) % tabs.length);
    });
  });

  container.querySelectorAll<HTMLButtonElement>('.copy-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const target = document.getElementById(btn.dataset.copyTarget!);
      if (!target) return;
      navigator.clipboard.writeText(target.textContent ?? '').then(() => {
        btn.textContent = 'Copied!';
        setTimeout(() => (btn.textContent = 'Copy'), 1500);
      });
    });
  });
}
```

Nota: os snippets nos `code(...)` são os verificados em 01/08/2026 — não alterar conteúdo, só formatação se o lint exigir.

- [ ] **Step 2: Rotear `/docs/mcp` em `main.ts`**

No parser de rota (hoje: `if (parts[0] === 'docs') return { page: 'docs' };` — linha ~27), passar a:

```typescript
  if (parts[0] === 'docs' && parts[1] === 'mcp') return { page: 'mcp' };
  if (parts[0] === 'docs') return { page: 'docs' };
```

Em `getPageTitle` acrescentar `case 'mcp': return 'MCP Server - SWAPI';`. No bloco de nav ativo, acrescentar `if (route.page === 'mcp' && href === '/docs/mcp') link.classList.add('active');` (e garantir que o link `/docs` NÃO fique ativo em `/docs/mcp`). No dispatcher de `navigate()`, importar `renderMcp` de `./pages/mcp` e adicionar o case chamando `renderMcp(container)` seguindo o padrão dos outros cases.

- [ ] **Step 3: Nav e callout**

`index.html` (nav-links, após Documentation):

```html
            <a href="/docs/mcp" class="nav-link">MCP</a>
```

`home.ts` — dentro da `<section class="hero">`, após o subtitle:

```html
      <a href="/docs/mcp" class="mcp-callout">
        <span class="mcp-callout-badge">NEW</span>
        Now also a remote MCP server — stateless spec 2026-07-28. Point your AI agent at it &rarr;
      </a>
```

- [ ] **Step 4: CSS (append em `style.css`)**

```css
/* MCP page */
.mcp-callout {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1rem;
  padding: 0.5rem 1rem;
  background: var(--accent-dim);
  border: 1px solid var(--accent);
  border-radius: var(--radius);
  color: var(--text-primary);
  text-decoration: none;
  font-size: 0.9rem;
}
.mcp-callout:hover { background: rgba(255, 215, 0, 0.25); }
.mcp-callout-badge {
  background: var(--accent);
  color: #0d1117;
  font-weight: 700;
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
}
.spec-callout {
  margin-top: 1rem;
  padding: 1rem;
  background: var(--bg-secondary);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius);
  color: var(--text-secondary);
  line-height: 1.6;
}
.spec-callout strong { color: var(--text-primary); }
.code-block { position: relative; margin: 0.75rem 0; }
.code-pre {
  background: var(--bg-tertiary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 0.85rem;
  line-height: 1.5;
  white-space: pre;
}
.copy-btn {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-secondary);
  font-size: 0.75rem;
  padding: 0.2rem 0.6rem;
  cursor: pointer;
}
.copy-btn:hover { color: var(--text-primary); border-color: var(--accent); }
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  border-bottom: 1px solid var(--border);
  margin: 1rem 0 0;
}
.tab-btn {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 0.9rem;
  padding: 0.5rem 0.9rem;
  cursor: pointer;
}
.tab-btn:hover { color: var(--text-primary); }
.tab-btn.active { color: var(--accent); border-bottom-color: var(--accent); }
.tab-panel { padding: 1rem 0.25rem; line-height: 1.7; }
.tab-panel code {
  background: var(--bg-tertiary);
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 0.85em;
}
.guide-steps { padding-left: 1.25rem; }
.guide-steps li { margin: 0.4rem 0; }
.guide-verify { margin-top: 0.75rem; }
.guide-note { color: var(--text-secondary); font-size: 0.85rem; margin-top: 0.5rem; }
.tools-table { width: 100%; border-collapse: collapse; margin-top: 0.75rem; }
.tools-table th, .tools-table td {
  text-align: left;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--border);
}
.tools-table th { color: var(--text-secondary); font-weight: 600; }
.prompt-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
  margin: 0.5rem 0;
  font-style: italic;
  color: var(--text-secondary);
}
.mcp-endpoint, .mcp-tools, .mcp-setup, .mcp-prompts, .mcp-trouble { margin-top: 2rem; }
```

Se o `style.css` já tiver classes com esses nomes (verificar com grep), renomear as novas com prefixo `mcp-` e ajustar o TS.

- [ ] **Step 5: Build do frontend (pega erros de TS/lint)**

Run: `cd swapi-app/src/main/webui && npm ci --no-audit --no-fund 2>/dev/null || npm install; npm run build`
Expected: build Vite sem erros.

- [ ] **Step 6: Verificação visual em dev mode**

Rodar `cd swapi-app && ./mvnw quarkus:dev` (background), aguardar subir e verificar:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5432/docs/mcp   # 200 (SPA fallback)
curl -s -X POST http://localhost:5432/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -o /dev/null -w '%{http_code}\n' -d '{}'   # não-404 (endpoint segue vivo)
```

Se houver browser disponível (preview), conferir: nav "MCP", callout na home, abas alternando, botão Copy. Encerrar o dev mode ao final.

- [ ] **Step 7: Commit**

```bash
git add swapi-app/src/main/webui/src/pages/mcp.ts swapi-app/src/main/webui/src/main.ts \
        swapi-app/src/main/webui/index.html swapi-app/src/main/webui/src/pages/home.ts \
        swapi-app/src/main/webui/src/style.css
git commit -m "Add /docs/mcp page with per-client setup tabs, nav link and home callout"
```

---

### Task 3: README — seção MCP expandida

**Files:**
- Modify: `README.md` (substituir a seção `## MCP Server` inteira, mantendo posição)

**Interfaces:**
- Consumes: nada de código; conteúdo fixo abaixo.
- Produces: seção README final; nenhuma task depende dela.

- [ ] **Step 1: Substituir a seção `## MCP Server` por:**

````markdown
## MCP Server

swapi.build is also a remote [MCP](https://modelcontextprotocol.io) server — built on the
**stateless MCP spec (2026-07-28)**: every request is self-contained, with no `initialize`
handshake and no session ids. First-party, read-only, no authentication:

```
https://swapi.build/mcp
```

Full setup guides: **[swapi.build/docs/mcp](https://swapi.build/docs/mcp)**

| Tool | Arguments | Returns |
|------|-----------|---------|
| `sw_list` | `resource` | All entities of a resource |
| `sw_get` | `resource`, `id` | One entity by id |
| `sw_random` | `resource` | A random entity |
| `sw_search` | `resource`, `query` | Name/title substring match |

`resource` is one of `PEOPLE`, `FILMS`, `PLANETS`, `SPECIES`, `STARSHIPS`, `VEHICLES`.
For `FILMS`, ids are episode ids (e.g. `4` = A New Hope).

<details>
<summary><strong>Claude Code</strong></summary>

```bash
claude mcp add --transport http swapi-build https://swapi.build/mcp
```

Or share via `.mcp.json` at the repo root:

```json
{
  "mcpServers": {
    "swapi-build": { "type": "http", "url": "https://swapi.build/mcp" }
  }
}
```

Verify: `claude mcp list` → `swapi-build ✔ Connected`.
</details>

<details>
<summary><strong>Claude Desktop &amp; claude.ai</strong></summary>

Settings → Connectors → **Add custom connector** → name `swapi-build`, URL
`https://swapi.build/mcp`. No authentication needed. Verify in any chat via the **+** menu → Connectors.
</details>

<details>
<summary><strong>OpenAI Codex</strong></summary>

```bash
codex mcp add swapi-build --url https://swapi.build/mcp
```

Or in `~/.codex/config.toml` (shared by CLI, IDE extension and ChatGPT desktop):

```toml
[mcp_servers.swapi-build]
url = "https://swapi.build/mcp"
```

Verify: `codex mcp list`.
</details>

<details>
<summary><strong>GitHub Copilot (VS Code)</strong></summary>

`.vscode/mcp.json` (top-level key is `servers`):

```json
{
  "servers": {
    "swapi-build": { "type": "http", "url": "https://swapi.build/mcp" }
  }
}
```

Or Command Palette → **MCP: Add Server**. Verify via the **Configure Tools** button in Copilot Chat.
On Business/Enterprise, the "MCP servers in Copilot" org policy must be enabled.
</details>

<details>
<summary><strong>IBM Bob</strong></summary>

`~/.bob/mcp.json` (global) or `.bob/mcp.json` (project):

```json
{
  "mcpServers": {
    "swapi-build": {
      "type": "streamable-http",
      "url": "https://swapi.build/mcp",
      "disabled": false
    }
  }
}
```

Or Bob panel → MCP tab → **Edit Global MCP**. Bob detects the tools automatically.
</details>

> The server scales to zero when idle — if the very first connection attempt fails, retry once
> (cold start is milliseconds; stateless requests are immune after that).
````

- [ ] **Step 2: Sanidade do markdown**

Run: `grep -c '^```' README.md`
Expected: número par. Conferir também que `<details>`/`</details>` estão balanceados: `grep -c '<details>' README.md` == `grep -c '</details>' README.md` (5 cada).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Expand README MCP section: spec emphasis, tools table, per-client guides"
```

---

### Task 4: Bump 1.9.1 + deploy preview → produção + verificação

**Files:**
- Modify: `swapi-app/pom.xml` (`<version>1.9.1</version>`), `swapi-app/src/main/resources/application.properties` (`quarkus.container-image.tag=1.9.1`)

**Interfaces:**
- Consumes: Tasks 1–3 commitadas; pipeline Vercel existente (`.vercel/` do checkout principal; `.env` na raiz do checkout principal com `VERCEL_API_TOKEN`).
- Produces: produção atualizada em https://swapi.build (site + polish).

- [ ] **Step 1: Bump + suíte completa**

Editar as duas versões para `1.9.1`. Run: `cd swapi-app && ./mvnw test` — PASS.

```bash
git add swapi-app/pom.xml swapi-app/src/main/resources/application.properties
git commit -m "Bump version to 1.9.1 (MCP docs + polish)"
```

- [ ] **Step 2: Preparar deploy (do worktree)**

```bash
cp -r /Users/eldermoraes/git/eldermoraes/swapi.build/swapi-app/.vercel swapi-app/ 2>/dev/null || true
cd swapi-app
set -a; source /Users/eldermoraes/git/eldermoraes/swapi.build/.env; set +a
```

Nunca imprimir o token.

- [ ] **Step 3: Deploy preview + verificação**

```bash
npx vercel deploy --token "$VERCEL_API_TOKEN" 2>&1 | tee /tmp/vercel-docs-deploy.log
PREVIEW_URL=$(grep -Eo 'https://[a-z0-9.-]+\.vercel\.app' /tmp/vercel-docs-deploy.log | tail -1)
```

Build ~10–25 min (rodar em background e poll se estourar timeout). Com Ready (usar o bypass `_vercel_share` documentado se 401):
- `GET $PREVIEW_URL/docs/mcp` → 200 (html da SPA)
- `GET $PREVIEW_URL/api/people/1` → 202
- `POST $PREVIEW_URL/mcp` (tools/list, formato do task-5-report da rodada anterior) → 4 tools, e o resultado de `tools/list` agora com `destructiveHint: false`

- [ ] **Step 4: Deploy produção + verificação**

```bash
npx vercel deploy --prod --token "$VERCEL_API_TOKEN"
```

Verificar em https://swapi.build: `/docs/mcp` 200, `/api/people/1` 202, `POST /mcp` com 4 tools e `destructiveHint: false`, `serverInfo.version` = 1.9.1.

- [ ] **Step 5: Sem push** — integração via controller.

---

## Riscos e observações

1. Colisão de path resolvida por design: página em `/docs/mcp`; `GET /mcp` continua 405 (comportamento do endpoint, correto).
2. Se `enable-spa-routing` não cobrir `/docs/mcp` em produção (rewrites da Vercel/Quinoa), o fallback é verificar em preview — se 404, investigar config Quinoa (`quarkus.quinoa.enable-spa-routing=true` já cobre paths não-API em dev e no jar; o container serve igual).
3. Snippets são cópia fiel de docs oficiais de 01/08/2026 — qualquer "correção" de sintaxe neles é regressão.
