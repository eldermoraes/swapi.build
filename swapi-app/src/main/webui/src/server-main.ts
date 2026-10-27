import type { Route } from './routes';
import { escapeHtml } from './utils';

/**
 * The <main> the server draws (2.11.0, slice E1). On the first load the client
 * adopts it as it is; on a later in-site navigation it fetches the document of
 * the new path and takes its <main>. The marker is `data-ssr-page` on
 * `#main-content` (ServerRendering.java); on a record address (2.12.0, slice
 * E2) also `data-ssr-type` and `data-ssr-id`, which must be the route's.
 *
 * Never a reload loop: a full load (`location.assign`) happens only for a path
 * other than the document this tab loaded. On that same path, a document
 * without the marker shows a static error with a "Reload" link instead.
 */

export type LoadResult = 'ok' | 'not-found' | 'reload' | 'unavailable' | 'error' | 'aborted';

/** Paths kept in memory, so back and forward do not ask (or count) again. */
export const CACHE_LIMIT = 32;

/** A page that has not answered in this time is an error (the alert), not a wait forever. */
export const FETCH_TIMEOUT_MS = 10_000;

const cache = new Map<string, Element>();
let documentPath = window.location.pathname;
let assign: (url: string) => void = (url) => window.location.assign(url);
let timeoutMs = FETCH_TIMEOUT_MS;

/** Test hook: empty the cache; the path of the loaded document; the full-load function; the timeout. */
export function resetServerMain(
  options: { documentPath?: string; assign?: (url: string) => void; timeoutMs?: number } = {},
): void {
  cache.clear();
  documentPath = options.documentPath ?? window.location.pathname;
  assign = options.assign ?? ((url) => window.location.assign(url));
  timeoutMs = options.timeoutMs ?? FETCH_TIMEOUT_MS;
}

/**
 * Drops `path` from the cache: a <main> whose marker matched but whose content
 * the caller rejected (2.12.0, an invalid record) must be asked again.
 */
export function forgetServerMain(path: string): void {
  cache.delete(path);
}

export interface LoadOptions {
  /** Aborted by a newer navigation: the result is 'aborted'. */
  signal?: AbortSignal;
  /** Checked right before the swap: false means the screen moved on ('aborted'). */
  isCurrent?: () => boolean;
  /** The query of the target URL, kept by the full load. */
  search?: string;
}

/** The marker matches the route; a record (2.12.0) must carry its own type and id too. */
function matches(main: Element, route: Route): boolean {
  if (main.getAttribute('data-ssr-page') !== route.page) return false;
  if (route.page !== 'resource-detail') return true;
  return (
    main.getAttribute('data-ssr-type') === route.type &&
    main.getAttribute('data-ssr-id') === route.id
  );
}

function unmark(main: Element): void {
  main.removeAttribute('data-ssr-page');
  main.removeAttribute('data-ssr-type');
  main.removeAttribute('data-ssr-id');
}

/** Adopts the server's <main> when its marker is this route's; never touches its children. */
export function adoptServerMain(container: HTMLElement, route: Route): boolean {
  if (!matches(container, route)) return false;
  unmark(container);
  return true;
}

/** Puts the children of a fetched <main> in the container (a copy: the cache keeps its own). */
export function swapServerMain(container: HTMLElement, main: Element): void {
  container.replaceChildren(
    ...Array.from(main.childNodes, (node) => document.importNode(node, true)),
  );
  unmark(container);
}

/**
 * The request's signal: the caller's plus the timeout. The timeout aborts only
 * the request; the caller's signal alone means 'aborted'. A browser without
 * AbortSignal.any or AbortSignal.timeout (Safari before 17.4) keeps the
 * caller's signal and no timeout, rather than failing every navigation.
 */
function withTimeout(signal?: AbortSignal): AbortSignal | undefined {
  if (typeof AbortSignal.timeout !== 'function') return signal;
  if (!signal) return AbortSignal.timeout(timeoutMs);
  if (typeof AbortSignal.any !== 'function') return signal;
  return AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]);
}

/**
 * The fetched document of `path`, without touching the page: `main` on 'ok'
 * (the swap is the caller's, swapServerMain), and on 'error' the HTTP status,
 * 0 when the request itself failed (network, timeout). The explorer uses this
 * for a record (2.12.0), so it can read the record before it commits the page.
 */
export interface FetchedMain {
  result: LoadResult;
  main?: Element;
  status?: number;
}

export async function fetchServerMain(
  path: string,
  route: Route,
  options: LoadOptions = {},
): Promise<FetchedMain> {
  const { signal, isCurrent = () => true, search = '' } = options;
  const cached = cache.get(path);
  if (cached) {
    if (!isCurrent()) return { result: 'aborted' };
    return { result: 'ok', main: cached };
  }

  let main: Element | null;
  try {
    const res = await fetch(path, {
      headers: { Accept: 'text/html' },
      signal: withTimeout(signal),
    });
    if (res.status === 404) return { result: signal?.aborted ? 'aborted' : 'not-found' };
    const type = res.headers.get('Content-Type') ?? '';
    if (!res.ok || !type.toLowerCase().startsWith('text/html')) {
      return signal?.aborted ? { result: 'aborted' } : { result: 'error', status: res.status };
    }
    const text = await res.text();
    if (signal?.aborted) return { result: 'aborted' };
    const doc = new DOMParser().parseFromString(text, 'text/html');
    main = doc.querySelector('main#main-content');
  } catch {
    return signal?.aborted ? { result: 'aborted' } : { result: 'error', status: 0 };
  }

  if (!isCurrent()) return { result: 'aborted' };
  if (!main || !matches(main, route)) {
    if (path !== documentPath) {
      assign(path + search);
      return { result: 'reload' };
    }
    return { result: 'unavailable' };
  }

  cache.delete(path);
  cache.set(path, main);
  while (cache.size > CACHE_LIMIT) cache.delete(cache.keys().next().value!);
  return { result: 'ok', main };
}

/** Fetches the document of `path` and puts its <main> in the container. */
export async function loadServerMain(
  container: HTMLElement,
  path: string,
  route: Route,
  options: LoadOptions = {},
): Promise<LoadResult> {
  const fetched = await fetchServerMain(path, route, options);
  if (fetched.result === 'ok') swapServerMain(container, fetched.main!);
  return fetched.result;
}

/**
 * The static block of a first load without the marker (the server failed to
 * draw the page): no request and no reload. `target` keeps the link away from
 * the SPA click handler, so "Reload" is a real load, chosen by the reader.
 */
export function renderUnavailable(
  container: HTMLElement,
  path: string,
  title = 'This page could not be loaded',
): void {
  container.innerHTML = `
    <div class="sw-inner-prose">
      <h1 class="sw-page-title">${escapeHtml(title)}</h1>
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
