import { highlightJson } from '../json-highlight';
import { escapeHtml } from '../utils';

/**
 * The /docs page comes drawn by the server from the OpenAPI document (2.11.0,
 * OpenApiDocs.java and templates/ssr/docs.html). The "Try it" buttons arrive as
 * `type="button"`, so without JavaScript a form never submits to `/docs?…`;
 * the client turns them into submit buttons and binds the request.
 */
export function bindDocumentation(container: HTMLElement): void {
  container.querySelectorAll<HTMLFormElement>('.try-form').forEach((form) => {
    form.querySelectorAll<HTMLButtonElement>('button.try-button').forEach((button) => {
      button.type = 'submit';
    });
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
