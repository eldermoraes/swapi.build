import { fetchEndpoint, ApiError } from '../api';
import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';
import { RESOURCES } from '../constants';

const SUGGESTIONS = ['people/1', 'planets/3', 'starships/9', 'films/1', 'species/1'];

export function renderHome(container: HTMLElement): void {
  container.innerHTML = `
    <section class="hero">
      <h1>SWAPI</h1>
      <p class="subtitle">The Star Wars API. All the Star Wars data you've ever wanted.</p>
      <a href="/docs/mcp" class="mcp-callout">
        <span class="mcp-callout-badge">NEW</span>
        Now also a remote MCP server — Streamable HTTP, any client. Point your AI agent at it &rarr;
      </a>
    </section>

    <section class="try-it">
      <h2>Try it now</h2>
      <div class="input-row">
        <span class="url-prefix">/api/</span>
        <label for="try-input" class="sr-only">API endpoint path</label>
        <input type="text" class="input-field" id="try-input" placeholder="people/1" />
        <button class="btn" id="try-btn">Request</button>
      </div>
      <div class="suggestions" role="group" aria-label="Suggestions">
        ${SUGGESTIONS.map((s) => `<span class="suggestion" role="button" tabindex="0" data-path="${s}">${s}</span>`).join('')}
      </div>
      <div id="try-result" aria-live="polite"></div>
    </section>

    <h3 class="resources-heading">Available Resources</h3>
    <div class="resource-grid">
      ${RESOURCES.map(
        (r) => `
        <a href="/resource/${r.key}" class="resource-card">
          <div class="card-icon">${r.icon}</div>
          <div class="card-title">${r.title}</div>
          <div class="card-endpoint">${r.endpoint}</div>
        </a>
      `,
      ).join('')}
    </div>
  `;

  const input = document.getElementById('try-input') as HTMLInputElement;
  const btn = document.getElementById('try-btn')!;
  const resultDiv = document.getElementById('try-result')!;

  async function doRequest() {
    const path = input.value.trim();
    if (!path) return;
    resultDiv.innerHTML = '<div class="loading"><div class="spinner"></div></div>';
    try {
      const { data, status } = await fetchEndpoint(path);
      resultDiv.innerHTML = `
        <div class="result-panel">
          <div class="result-header">
            <span class="result-status">GET /api/${escapeHtml(path)} <span class="status-code">${status}</span></span>
          </div>
          <pre class="result-body">${highlightJson(data)}</pre>
        </div>
      `;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      const message = err instanceof ApiError ? err.message : 'Unknown error';
      resultDiv.innerHTML = `<div class="error-message">${escapeHtml(message)}</div>`;
    }
  }

  btn.addEventListener('click', doRequest);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doRequest();
  });

  container.querySelectorAll('.suggestion').forEach((el) => {
    function activate() {
      input.value = (el as HTMLElement).dataset.path || '';
      doRequest();
    }
    el.addEventListener('click', activate);
    el.addEventListener('keydown', (e) => {
      if ((e as KeyboardEvent).key === 'Enter' || (e as KeyboardEvent).key === ' ') {
        e.preventDefault();
        activate();
      }
    });
  });
}
