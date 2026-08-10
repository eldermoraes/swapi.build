import { fetchEndpoint, ApiError } from '../api';
import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';

export function pill(
  label: string,
  href: string,
  kind: 'solid' | 'ghost' = 'solid',
  small = false,
): string {
  const classes = ['sw-pill', `sw-pill--${kind}`, small ? 'sw-pill--sm' : '']
    .filter(Boolean)
    .join(' ');
  return `<a class="${classes}" href="${escapeHtml(href)}">${escapeHtml(label)}</a>`;
}

export function sectionLabel(text: string): string {
  return `<h2 class="sw-section-label">${escapeHtml(text)}</h2>`;
}

export interface TerminalOptions {
  idPrefix: string;
  initialPath?: string;
  suggestions?: string[];
}

export function terminalMarkup(opts: TerminalOptions): string {
  const chips = (opts.suggestions ?? [])
    .map((s) => `<button type="button" data-path="${escapeHtml(s)}">${escapeHtml(s)}</button>`)
    .join('');
  return `
    <div class="sw-term" data-term="${escapeHtml(opts.idPrefix)}">
      <div class="sw-term-head"><span>GALACTIC TERMINAL</span><span class="sw-term-live">● LIVE</span></div>
      <div class="sw-term-prompt">
        <span class="sw-term-prefix" aria-hidden="true">GET /api/</span>
        <label class="sr-only" for="${escapeHtml(opts.idPrefix)}-term-input">API endpoint path</label>
        <input id="${escapeHtml(opts.idPrefix)}-term-input" type="text"
               value="${escapeHtml(opts.initialPath ?? '')}" placeholder="people/1"
               autocomplete="off" spellcheck="false" />
        <button type="button" class="sw-term-exec">EXEC</button>
      </div>
      <pre class="sw-term-out" aria-live="polite"></pre>
      ${chips ? `<div class="sw-term-chips" role="group" aria-label="Suggestions">${chips}</div>` : ''}
    </div>`;
}

export function initTerminal(container: HTMLElement, opts: TerminalOptions): void {
  const root = container.querySelector<HTMLElement>(`[data-term="${opts.idPrefix}"]`);
  if (!root) return;
  const input = root.querySelector<HTMLInputElement>('input')!;
  const exec = root.querySelector<HTMLButtonElement>('.sw-term-exec')!;
  const out = root.querySelector<HTMLPreElement>('.sw-term-out')!;

  async function run(): Promise<void> {
    const path = input.value.trim();
    if (!path) return;
    out.innerHTML = '<span class="sw-term-status">… querying the galaxy</span>';
    try {
      const { data, status } = await fetchEndpoint(path);
      out.innerHTML =
        `<span class="sw-term-status">${status} OK · GET /api/${escapeHtml(path)}</span>` +
        highlightJson(data);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      const message = err instanceof ApiError ? err.message : 'Unknown error';
      out.innerHTML = `<span class="sw-term-error">${escapeHtml(message)}</span>`;
    }
  }

  exec.addEventListener('click', () => void run());
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') void run();
  });
  root.querySelectorAll<HTMLButtonElement>('.sw-term-chips button').forEach((chip) => {
    chip.addEventListener('click', () => {
      input.value = chip.dataset.path ?? '';
      void run();
    });
  });
}

export interface IndexRowData {
  title: string;
  endpoint: string;
  href: string;
}

export function indexRows(rows: IndexRowData[]): string {
  return `<div class="sw-index">${rows
    .map(
      (r) => `<a class="sw-row" href="${escapeHtml(r.href)}">
        <span class="sw-row-title">${escapeHtml(r.title)}</span>
        <span class="sw-row-endpoint">${escapeHtml(r.endpoint)}</span>
        <span class="sw-row-go" aria-hidden="true">→</span>
      </a>`,
    )
    .join('')}</div>`;
}
