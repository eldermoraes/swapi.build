import { fetchOpenApiSpec } from '../api';
import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';
import type { OpenApiOperation, OpenApiSchemaObj, OpenApiSpec } from '../types';

const SCHEMA_REF_PREFIX = '#/components/schemas/';

// main.ts roteia só `/docs` para esta página (`/docs/mcp` é outra) — usado para
// abortar a pintura quando o usuário navegou durante o fetch da spec.
function isDocsRoute(): boolean {
  const parts = window.location.pathname.split('/').filter(Boolean);
  return parts.length === 1 && parts[0] === 'docs';
}

// Ordem de exibição: Root primeiro, demais na ordem declarada na spec
function orderedTags(spec: OpenApiSpec): string[] {
  const declared = (spec.tags ?? []).map((t) => t.name);
  const seen = new Set<string>();
  for (const item of Object.values(spec.paths)) {
    for (const tag of item.get?.tags ?? []) seen.add(tag);
  }
  const ordered = declared.filter((t) => seen.has(t));
  for (const t of seen) if (!ordered.includes(t)) ordered.push(t);
  return ordered.includes('Root') ? ['Root', ...ordered.filter((t) => t !== 'Root')] : ordered;
}

function operationsByTag(spec: OpenApiSpec, tag: string): { path: string; op: OpenApiOperation }[] {
  return Object.entries(spec.paths)
    .filter(([, item]) => item.get?.tags?.includes(tag))
    .map(([path, item]) => ({ path, op: item.get! }))
    .sort((a, b) => a.path.localeCompare(b.path));
}

// O nome do schema vem da própria spec: primeiro $ref das respostas da tag
// (direto nas operações by-id, dentro de `items` nas de lista).
function schemaNameForTag(operations: { op: OpenApiOperation }[]): string | undefined {
  for (const { op } of operations) {
    for (const response of Object.values(op.responses)) {
      for (const media of Object.values(response.content ?? {})) {
        const ref = media.schema?.$ref ?? media.schema?.items?.$ref;
        if (ref?.startsWith(SCHEMA_REF_PREFIX)) return ref.slice(SCHEMA_REF_PREFIX.length);
      }
    }
  }
  return undefined;
}

function endpointBlock(path: string, op: OpenApiOperation): string {
  const search = op.parameters?.find((p) => p.in === 'query' && p.name === 'search');
  const idParam = op.parameters?.find((p) => p.in === 'path' && p.name === 'id');
  const displayPath = search ? `${path}?search=${search.example ?? 'value'}` : path;
  const desc = [op.summary, op.description]
    .filter(Boolean)
    .map((s) => escapeHtml(s!))
    .join(' — ');
  const has404 = Boolean(op.responses['404']);

  const inputs = [
    idParam
      ? `<input class="input-field try-input" name="id" type="number" min="1"
           placeholder="id" value="${escapeHtml(String(idParam.example ?? '1'))}"
           aria-label="${escapeHtml(idParam.description ?? 'id')}">`
      : '',
    search
      ? `<input class="input-field try-input" name="search" type="text"
           placeholder="search (optional)" value=""
           aria-label="${escapeHtml(search.description ?? 'search')}">`
      : '',
  ].join('');

  return `
    <div class="endpoint-block" data-path="${escapeHtml(path)}">
      <div class="endpoint-method">
        <span class="method-badge">GET</span>
        <span class="endpoint-path">${escapeHtml(displayPath)}</span>
      </div>
      <div class="endpoint-desc">${desc}${has404 ? ' <span class="status-note">200 / 404</span>' : ''}</div>
      <form class="try-form">
        ${inputs}
        <button type="submit" class="btn try-button">Try it</button>
      </form>
      <div class="try-result" aria-live="polite" hidden></div>
    </div>`;
}

function schemaTable(name: string, schema: OpenApiSchemaObj): string {
  const rows = Object.entries(schema.properties ?? {})
    .map(([field, prop]) => {
      const type =
        prop.type === 'array' ? `array of ${prop.items?.type ?? 'string'}` : (prop.type ?? '');
      return `<tr>
        <td class="schema-field">${escapeHtml(field)}</td>
        <td class="schema-type">${escapeHtml(type)}</td>
        <td>${escapeHtml(prop.description ?? '')}</td>
      </tr>`;
    })
    .join('');
  return `
    <details class="schema-details">
      <summary>${escapeHtml(name)} fields</summary>
      <table class="schema-table">
        <thead><tr><th>Field</th><th>Type</th><th>Description</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </details>`;
}

export async function renderDocumentation(container: HTMLElement): Promise<void> {
  container.innerHTML = `<div class="docs"><h1>Documentation</h1><p class="docs-intro">Loading API specification…</p></div>`;

  let spec: OpenApiSpec;
  try {
    spec = await fetchOpenApiSpec();
  } catch {
    if (!isDocsRoute()) return; // navegou durante o fetch: não sobrescrever a página nova
    container.innerHTML = `
      <div class="docs">
        <h1>Documentation</h1>
        <p class="docs-intro">Could not load the API specification right now.
        The raw spec is available at
        <a href="/openapi.json" target="_blank" rel="noopener noreferrer">/openapi.json</a>.</p>
      </div>`;
    return;
  }

  if (!isDocsRoute()) return; // navegou durante o fetch: não sobrescrever a página nova

  const tagDescriptions = new Map((spec.tags ?? []).map((t) => [t.name, t.description ?? '']));
  const sections = orderedTags(spec)
    .map((tag) => {
      const operations = operationsByTag(spec, tag);
      const schemaName = schemaNameForTag(operations);
      const schema = schemaName ? spec.components?.schemas?.[schemaName] : undefined;
      return `
        <h2>${escapeHtml(tag)}</h2>
        ${tagDescriptions.get(tag) ? `<p class="tag-desc">${escapeHtml(tagDescriptions.get(tag)!)}</p>` : ''}
        ${operations.map(({ path, op }) => endpointBlock(path, op)).join('')}
        ${schema && schemaName ? schemaTable(schemaName, schema) : ''}`;
    })
    .join('');

  container.innerHTML = `
    <div class="docs">
      <h1>Documentation</h1>
      <p class="docs-intro">${escapeHtml(spec.info.description ?? '')}</p>
      <p class="spec-link">
        OpenAPI ${escapeHtml(spec.openapi)} · version ${escapeHtml(spec.info.version)} ·
        <a href="/openapi.json" download="openapi.json">Download the spec</a> and generate a client:
        <code>npx @openapitools/openapi-generator-cli generate -i /openapi.json -g typescript-fetch</code>
      </p>
      ${sections}
    </div>`;

  container.querySelectorAll<HTMLFormElement>('.try-form').forEach((form) => {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const block = form.closest<HTMLElement>('.endpoint-block')!;
      const result = block.querySelector<HTMLElement>('.try-result')!;
      const idInput = form.querySelector<HTMLInputElement>('input[name="id"]');
      const searchInput = form.querySelector<HTMLInputElement>('input[name="search"]');

      let url = block.dataset.path!;
      if (idInput) url = url.replace('{id}', encodeURIComponent(idInput.value || '1'));
      if (searchInput?.value) url += `?search=${encodeURIComponent(searchInput.value)}`;

      result.hidden = false;
      result.innerHTML = '<p class="try-status">Loading…</p>';
      try {
        const res = await fetch(url);
        const statusLine = `<p class="try-status">GET ${escapeHtml(url)} → HTTP ${res.status}</p>`;
        const text = await res.text();
        let bodyHtml: string;
        try {
          bodyHtml = `<pre class="try-json">${highlightJson(JSON.parse(text))}</pre>`;
        } catch {
          bodyHtml = `<pre class="try-json">${escapeHtml(text)}</pre>`; // 404 devolve text/plain
        }
        result.innerHTML = statusLine + bodyHtml;
      } catch {
        result.innerHTML = '<p class="try-status">Network error — check your connection</p>';
      }
    });
  });
}
