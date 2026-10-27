import { fetchEndpoint, ApiError } from '../api';
import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';

/**
 * The pill link. The server draws the same markup with the Qute tag
 * templates/tags/pill.html (2.11.0); components.test.ts keeps the two equal.
 */
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

/**
 * Binds the terminal the server drew (`[data-term="<idPrefix>"]`, 2.11.0,
 * templates/ssr/home.html): EXEC, Enter and the suggestion chips, which it
 * reads from the DOM.
 */
export function initTerminal(container: HTMLElement, idPrefix: string): void {
  const root = container.querySelector<HTMLElement>(`[data-term="${idPrefix}"]`);
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
