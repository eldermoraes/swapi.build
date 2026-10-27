import { pill } from '../ui/components';
import { escapeHtml } from '../utils';

/**
 * The page for a path the server answers with 404 (2.9.1): an unknown page, or
 * an entity id that does not exist. The status comes from the server; this only
 * tells the visitor where to go instead.
 */
export function renderNotFound(container: HTMLElement, path: string): void {
  container.innerHTML = `
    <div class="about sw-inner-prose not-found">
      <h1 class="sw-page-title">Page not found</h1>
      <p>
        There is nothing at <code>${escapeHtml(path)}</code>. The page may have moved, or the id may
        not exist in the Episodes I–VI data.
      </p>
      <div class="sw-cta-row">
        ${pill('Go to the home page', '/')}
        ${pill('Browse the API', '/resource/people', 'ghost')}
        ${pill('Read the docs', '/docs', 'ghost')}
      </div>
    </div>
  `;
}
