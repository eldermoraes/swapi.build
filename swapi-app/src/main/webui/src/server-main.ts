import type { Route } from './routes';
import { escapeHtml } from './utils';

/**
 * The <main> the server draws (2.11.0, slice E1). On the first load the client
 * adopts it as it is; on a later in-site navigation it fetches the document of
 * the new path and takes its <main>. The marker is `data-ssr-page` on
 * `#main-content` (ServerRendering.java).
 *
 * Never a reload loop: a full load (`location.assign`) happens only for a path
 * other than the document this tab loaded. On that same path, a document
 * without the marker shows a static error with a "Reload" link instead.
 */

export type LoadResult = 'ok' | 'not-found' | 'reload' | 'unavailable' | 'error' | 'aborted';

/** Paths kept in memory, so back and forward do not ask (or count) again. */
export const CACHE_LIMIT = 32;

const cache = new Map<string, Element>();
let documentPath = window.location.pathname;
let assign: (url: string) => void = (url) => window.location.assign(url);

/** Test hook: empty the cache; the path of the loaded document; the full-load function. */
export function resetServerMain(
  options: { documentPath?: string; assign?: (url: string) => void } = {},
): void {
  cache.clear();
  documentPath = options.documentPath ?? window.location.pathname;
  assign = options.assign ?? ((url) => window.location.assign(url));
}

function matches(main: Element, route: Route): boolean {
  return main.getAttribute('data-ssr-page') === route.page;
}

function unmark(main: Element): void {
  main.removeAttribute('data-ssr-page');
}

/** Adopts the server's <main> when its marker is this route's; never touches its children. */
export function adoptServerMain(container: HTMLElement, route: Route): boolean {
  if (!matches(container, route)) return false;
  unmark(container);
  return true;
}

function swap(container: HTMLElement, main: Element): void {
  container.replaceChildren(
    ...Array.from(main.childNodes, (node) => document.importNode(node, true)),
  );
  unmark(container);
}

/** Fetches the document of `path` and puts its <main> in the container. */
export async function loadServerMain(
  container: HTMLElement,
  path: string,
  route: Route,
  signal?: AbortSignal,
): Promise<LoadResult> {
  const cached = cache.get(path);
  if (cached) {
    swap(container, cached);
    return 'ok';
  }

  let main: Element | null;
  try {
    const res = await fetch(path, { headers: { Accept: 'text/html' }, signal });
    if (res.status === 404) return signal?.aborted ? 'aborted' : 'not-found';
    const type = res.headers.get('Content-Type') ?? '';
    if (!res.ok || !type.toLowerCase().startsWith('text/html')) {
      return signal?.aborted ? 'aborted' : 'error';
    }
    const text = await res.text();
    if (signal?.aborted) return 'aborted';
    const doc = new DOMParser().parseFromString(text, 'text/html');
    main = doc.querySelector('main#main-content');
  } catch {
    return signal?.aborted ? 'aborted' : 'error';
  }

  if (!main || !matches(main, route)) {
    if (path !== documentPath) {
      assign(path);
      return 'reload';
    }
    return 'unavailable';
  }

  cache.delete(path);
  cache.set(path, main);
  while (cache.size > CACHE_LIMIT) cache.delete(cache.keys().next().value!);
  swap(container, main);
  return 'ok';
}

/**
 * The static block of a first load without the marker (the server failed to
 * draw the page): no request and no reload. `target` keeps the link away from
 * the SPA click handler, so "Reload" is a real load, chosen by the reader.
 */
export function renderUnavailable(container: HTMLElement, path: string): void {
  container.innerHTML = `
    <div class="sw-inner-prose">
      <h1 class="sw-page-title">This page could not be loaded</h1>
      <p><a href="${escapeHtml(path)}" target="_self">Reload</a></p>
    </div>`;
}

/** The alert of a failed in-site fetch; the content that was there stays. */
export function showLoadFailure(container: HTMLElement, path: string): void {
  container.querySelector('.sw-load-failure')?.remove();
  const alert = document.createElement('p');
  alert.className = 'sw-load-failure';
  alert.setAttribute('role', 'alert');
  alert.innerHTML = `This page could not be loaded. <a href="${escapeHtml(path)}" target="_self">Open it with a full page load</a>.`;
  container.prepend(alert);
}
