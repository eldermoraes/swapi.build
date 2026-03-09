import { fetchResources, searchResource, fetchResourceById, fetchRandom, ApiError } from '../api';
import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';
import { getResourceMeta } from '../constants';
import type { SWResource } from '../types';

export async function renderResourceList(container: HTMLElement, type: string): Promise<void> {
  const meta = getResourceMeta(type);

  container.innerHTML = `
    <div class="resource-browser">
      <div class="browser-header">
        <h1>${meta.title}</h1>
        <div class="browser-actions">
          <div class="search-box">
            <label for="search-input" class="sr-only">Search ${meta.title.toLowerCase()}</label>
            <input type="text" class="input-field" id="search-input" placeholder="Search ${meta.title.toLowerCase()}..." />
            <button class="btn" id="search-btn">Search</button>
          </div>
          <button class="btn btn-secondary" id="random-btn">Random</button>
        </div>
      </div>
      <div id="resource-content" aria-live="polite">
        <div class="loading"><div class="spinner"></div></div>
      </div>
    </div>
  `;

  const contentDiv = document.getElementById('resource-content')!;
  const searchInput = document.getElementById('search-input') as HTMLInputElement;
  const searchBtn = document.getElementById('search-btn')!;
  const randomBtn = document.getElementById('random-btn')!;

  function renderItems(items: SWResource[]) {
    if (!items || items.length === 0) {
      contentDiv.innerHTML = '<p style="color:var(--text-secondary)">No results found.</p>';
      return;
    }
    contentDiv.innerHTML = `
      <div class="item-list">
        ${(items as unknown as Record<string, unknown>[])
          .map((item, i) => {
            const name = (item[meta.nameField] || `Item ${i + 1}`) as string;
            const detail = (item[meta.detailField] || '') as string;
            const url = (item['url'] || '') as string;
            const idMatch = url.match(/\/(\d+)\/?$/);
            const id = idMatch ? idMatch[1] : String(i + 1);
            return `
            <a href="/resource/${type}/${id}" class="item-card">
              <div class="item-name">${escapeHtml(name)}</div>
              <div class="item-detail">${escapeHtml(detail)}</div>
            </a>
          `;
          })
          .join('')}
      </div>
    `;
  }

  function showJson(data: unknown) {
    contentDiv.innerHTML = `
      <div class="result-panel">
        <div class="result-header">
          <span class="result-status"><span class="status-code">200</span></span>
          <a href="/resource/${type}" class="back-btn">Back to list</a>
        </div>
        <pre class="result-body">${highlightJson(data)}</pre>
      </div>
    `;
  }

  function showError(action: string, err: unknown) {
    if (err instanceof DOMException && err.name === 'AbortError') return;
    const message = err instanceof ApiError ? err.message : 'Unknown error';
    contentDiv.innerHTML = `<div class="error-message">${escapeHtml(`${action}: ${message}`)}</div>`;
  }

  try {
    const items = await fetchResources<SWResource>(type);
    renderItems(items);
  } catch (err) {
    showError('Failed to load', err);
  }

  searchBtn.addEventListener('click', async () => {
    const q = searchInput.value.trim();
    if (!q) return;
    contentDiv.innerHTML = '<div class="loading"><div class="spinner"></div></div>';
    try {
      const results = await searchResource<SWResource>(type, q);
      renderItems(results);
    } catch (err) {
      showError('Search failed', err);
    }
  });

  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') searchBtn.click();
  });

  randomBtn.addEventListener('click', async () => {
    contentDiv.innerHTML = '<div class="loading"><div class="spinner"></div></div>';
    try {
      const data = await fetchRandom(type);
      showJson(data);
    } catch (err) {
      showError('Failed', err);
    }
  });
}

export async function renderResourceDetail(
  container: HTMLElement,
  type: string,
  id: string,
): Promise<void> {
  const meta = getResourceMeta(type);

  container.innerHTML = `
    <div class="detail-view">
      <div class="detail-header">
        <a href="/resource/${type}" class="back-btn">&larr; ${meta.title}</a>
      </div>
      <div id="detail-content" aria-live="polite">
        <div class="loading"><div class="spinner"></div></div>
      </div>
    </div>
  `;

  const contentDiv = document.getElementById('detail-content')!;

  try {
    const data = await fetchResourceById<Record<string, unknown>>(type, id);
    const name = (data[meta.nameField] as string) || `${meta.title} #${id}`;
    const headerEl = container.querySelector('.detail-header')!;
    headerEl.innerHTML += `<h1 class="detail-title">${escapeHtml(name)}</h1>`;

    contentDiv.innerHTML = `
      <div class="result-panel">
        <div class="result-header">
          <span class="result-status">GET /api/${escapeHtml(type)}/${escapeHtml(id)} <span class="status-code">200</span></span>
        </div>
        <pre class="result-body">${highlightJson(data)}</pre>
      </div>
    `;
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') return;
    const message = err instanceof ApiError ? err.message : 'Unknown error';
    contentDiv.innerHTML = `<div class="error-message">${escapeHtml(`Failed to load: ${message}`)}</div>`;
  }
}
