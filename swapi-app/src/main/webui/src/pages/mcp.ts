const ENDPOINT = 'https://swapi.build/mcp';

interface ClientGuide {
  id: string;
  label: string;
  html: string;
}

function code(id: string, lang: string, content: string): string {
  return `
    <div class="code-block" data-copy-id="${id}">
      <button class="copy-btn" data-copy-target="${id}" aria-live="polite" aria-label="Copy to clipboard">Copy</button>
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
      ${code(
        'cc-json',
        'json',
        `{
  "mcpServers": {
    "swapi-build": {
      "type": "http",
      "url": "${ENDPOINT}"
    }
  }
}`,
      )}
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
      ${code(
        'cx-toml',
        'toml',
        `[mcp_servers.swapi-build]
url = "${ENDPOINT}"`,
      )}
      <p class="guide-verify"><strong>Verify:</strong> <code>codex mcp list</code>, or <code>/mcp</code> inside the TUI.</p>`,
  },
  {
    id: 'copilot',
    label: 'GitHub Copilot',
    html: `
      <p>In VS Code, create <code>.vscode/mcp.json</code> in your workspace (note the top-level key is <code>servers</code>):</p>
      ${code(
        'cp-json',
        'json',
        `{
  "servers": {
    "swapi-build": {
      "type": "http",
      "url": "${ENDPOINT}"
    }
  }
}`,
      )}
      <p>Or use the Command Palette: <strong>MCP: Add Server</strong> &rarr; HTTP &rarr; paste the URL.</p>
      <p class="guide-verify"><strong>Verify:</strong> open Copilot Chat and click the <strong>Configure Tools</strong> button — the four swapi tools appear under swapi-build.</p>
      <p class="guide-note">On Copilot Business/Enterprise, an admin must enable the "MCP servers in Copilot" policy. Visual Studio and JetBrains also support remote HTTP servers via their own <code>mcp.json</code>.</p>`,
  },
  {
    id: 'ibm-bob',
    label: 'IBM Bob',
    html: `
      <p>Add to <code>~/.bob/settings/mcp_settings.json</code> (global) or <code>.bob/mcp.json</code> in your project:</p>
      ${code(
        'bob-json',
        'json',
        `{
  "mcpServers": {
    "swapi-build": {
      "type": "streamable-http",
      "url": "${ENDPOINT}",
      "disabled": false
    }
  }
}`,
      )}
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
      <code>SPECIES</code>, <code>STARSHIPS</code>, <code>VEHICLES</code>. Ids are the record ids from each
      entity's <code>url</code> field (for <code>FILMS</code>, <code>1</code> = A New Hope).</p>
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
      retry once — the native binary starts in tens of milliseconds (the platform may take a bit longer
      to provision the container) and stateless requests are immune after that.</p>
      <p>Probing <code>/mcp</code> with raw <code>curl</code>? Stateless requests must include the
      <code>MCP-Protocol-Version</code>, <code>Mcp-Method</code> and <code>Mcp-Name</code> headers —
      MCP clients send these automatically.</p>
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
      if (e.key === 'Home') {
        e.preventDefault();
        selectTab(0);
      }
      if (e.key === 'End') {
        e.preventDefault();
        selectTab(tabs.length - 1);
      }
    });
  });

  container.querySelectorAll<HTMLButtonElement>('.copy-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const target = document.getElementById(btn.dataset.copyTarget!);
      if (!target) return;
      navigator.clipboard
        .writeText(target.textContent ?? '')
        .then(() => {
          btn.textContent = 'Copied!';
          setTimeout(() => (btn.textContent = 'Copy'), 1500);
        })
        .catch(() => {
          btn.textContent = 'Copy failed';
          setTimeout(() => (btn.textContent = 'Copy'), 1500);
        });
    });
  });
}
