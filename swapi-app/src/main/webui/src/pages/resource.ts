import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';
import { getResourceMeta } from '../constants';
import type { Entity, ResourceTool } from '../resource-actions';

export function renderResourceList(
  container: HTMLElement,
  type: string,
  items: Entity[],
  query: string,
  action: (tool: ResourceTool, query?: string) => void,
): void {
  const meta = getResourceMeta(type);
  container.innerHTML = `
    <div class="resource-browser sw-inner-wide">
      <div class="browser-header">
        <h1 class="sw-page-title">${meta.title}</h1>
        <div class="browser-actions">
          <div class="search-box">
            <label for="search-input" class="sr-only">Search ${meta.title.toLowerCase()}</label>
            <input type="text" class="search-input" id="search-input" placeholder="Search ${meta.title.toLowerCase()}..." value="${escapeHtml(query)}" />
            <button class="sw-pill sw-pill--solid sw-pill--sm" id="search-btn">Search</button>
          </div>
          <button class="sw-pill sw-pill--ghost sw-pill--sm" id="random-btn">Random</button>
        </div>
      </div>
      <div id="resource-content" aria-live="polite">
        ${
          items.length === 0
            ? '<p class="no-results">No results found.</p>'
            : `<div class="item-list">${items
                .map(
                  (item) => `
          <a href="/resource/${type}/${String(item.url).match(/\/(\d+)\/?$/)![1]}" class="item-card sw-panel">
            <div class="item-name">${escapeHtml(String(item[meta.nameField]))}</div>
            <div class="item-detail">${escapeHtml(String(item[meta.detailField] ?? ''))}</div>
          </a>`,
                )
                .join('')}</div>`
        }
      </div>
    </div>`;
  const input = container.querySelector<HTMLInputElement>('#search-input')!;
  container
    .querySelector('#search-btn')!
    .addEventListener('click', () => action('sw_search', input.value));
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') action('sw_search', input.value);
  });
  container.querySelector('#random-btn')!.addEventListener('click', () => action('sw_random'));
}

export function renderResourceDetail(
  container: HTMLElement,
  type: string,
  id: number,
  data: Entity,
  status: number,
): void {
  const meta = getResourceMeta(type);
  container.innerHTML = `
    <div class="detail-view sw-inner-wide">
      <div class="detail-header">
        <a href="/resource/${type}" class="back-btn">&larr; ${meta.title}</a>
        <h1 class="detail-title">${escapeHtml(String(data[meta.nameField]))}</h1>
      </div>
      <div id="detail-content" aria-live="polite">
        <div class="sw-code result-panel">
          <div class="result-header">
            <span class="result-status">GET /api/${type}/${id} <span class="status-code">${status}</span></span>
          </div>
          <pre class="result-body">${highlightJson(data)}</pre>
        </div>
      </div>
    </div>`;
}
