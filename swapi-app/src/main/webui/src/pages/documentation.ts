import { fetchOpenApiSpec } from '../api';
import { escapeHtml } from '../utils';
import type { OpenApiOperation, OpenApiSchemaObj, OpenApiSpec } from '../types';

// tag OpenAPI -> nome do schema em components.schemas
const TAG_SCHEMA: Record<string, string> = {
  People: 'People',
  Films: 'Film',
  Planets: 'Planet',
  Species: 'Specie',
  Starships: 'Starship',
  Vehicles: 'Vehicle',
};

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

function endpointBlock(path: string, op: OpenApiOperation): string {
  const search = op.parameters?.find((p) => p.in === 'query' && p.name === 'search');
  const displayPath = search ? `${path}?search=${search.example ?? 'value'}` : path;
  const desc = [op.summary, op.description].filter(Boolean).map((s) => escapeHtml(s!)).join(' — ');
  const has404 = Boolean(op.responses['404']);
  return `
    <div class="endpoint-block" data-path="${escapeHtml(path)}">
      <div class="endpoint-method">
        <span class="method-badge">GET</span>
        <span class="endpoint-path">${escapeHtml(displayPath)}</span>
      </div>
      <div class="endpoint-desc">${desc}${has404 ? ' <span class="status-note">200 / 404</span>' : ''}</div>
    </div>`;
}

function schemaTable(name: string, schema: OpenApiSchemaObj): string {
  const rows = Object.entries(schema.properties ?? {})
    .map(([field, prop]) => {
      const type = prop.type === 'array' ? `array of ${prop.items?.type ?? 'string'}` : (prop.type ?? '');
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
    container.innerHTML = `
      <div class="docs">
        <h1>Documentation</h1>
        <p class="docs-intro">Could not load the API specification right now.
        The raw spec is available at
        <a href="/openapi.json" target="_blank" rel="noopener noreferrer">/openapi.json</a>.</p>
      </div>`;
    return;
  }

  const tagDescriptions = new Map((spec.tags ?? []).map((t) => [t.name, t.description ?? '']));
  const sections = orderedTags(spec)
    .map((tag) => {
      const schemaName = TAG_SCHEMA[tag];
      const schema = schemaName ? spec.components?.schemas?.[schemaName] : undefined;
      return `
        <h2>${escapeHtml(tag)}</h2>
        ${tagDescriptions.get(tag) ? `<p class="tag-desc">${escapeHtml(tagDescriptions.get(tag)!)}</p>` : ''}
        ${operationsByTag(spec, tag).map(({ path, op }) => endpointBlock(path, op)).join('')}
        ${schema ? schemaTable(schemaName!, schema) : ''}`;
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
}
