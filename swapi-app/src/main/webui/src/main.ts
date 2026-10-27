import './style.css';
import { bindHome } from './pages/home';
import { ResourceExplorer } from './resource-actions';
import { registerWebMcp } from './webmcp';
import { renderWebMcp, updateWebMcpStatus } from './pages/webmcp';
import { bindDocumentation } from './pages/documentation';
import { bindMcp } from './pages/mcp';
import { bindRecord } from './pages/resource';
import { adoptServerMain, loadServerMain, renderUnavailable, showLoadFailure } from './server-main';
import { renderPrivacy } from './pages/privacy';
import { renderTerms } from './pages/terms';
import { renderStats } from './pages/stats';
import { renderNotFound } from './pages/not-found';
import { isServerPath } from './spa-links';
import { cancelPending } from './api';
import { applySeoMetadata, spokenTitle } from './seo';
import { activeNavHref } from './nav';
import { getRoute, isServerRendered, takeServerStatus, type Page, type Route } from './routes';

// No analytics or performance beacons in the frontend: the only measurement
// script, the Cloudflare Web Analytics beacon, is injected by the server
// (WebAnalytics.java) when a token is configured. no-tracking.test.ts keeps the
// frontend itself free of trackers.

const announcer = document.createElement('div');
announcer.setAttribute('aria-live', 'polite');
announcer.setAttribute('aria-atomic', 'true');
announcer.className = 'sr-only';
document.body.appendChild(announcer);

let navigationGeneration = 0;

function announce(message: string) {
  announcer.textContent = '';
  const generation = navigationGeneration;
  requestAnimationFrame(() => {
    if (generation !== navigationGeneration) return;
    announcer.textContent = message;
  });
}

function updateActiveNav(page: Page) {
  const active = activeNavHref(page);
  document.querySelectorAll('.nav-link').forEach((link) => {
    link.classList.toggle('active', active !== null && link.getAttribute('href') === active);
  });
}

// The first navigation reads the 404 marker the server put in the head; later
// navigations decide on the client (the marker is removed when read).
let firstNavigation = true;

function resolveRoute(path: string): Route {
  const route = getRoute(path);
  if (firstNavigation) {
    firstNavigation = false;
    if (takeServerStatus(document) === 404) return { page: 'not-found' };
  }
  return route;
}

// The path the head describes. On the first load it is the server's head, which
// is the truth (2.10.0): the client never rewrites it, not even with the same
// values, so a crawler that runs the script sees one title, one canonical and
// one robots. Later navigations write the head once, for a new path.
let headPath = window.location.pathname;

/** Writes the head for a path, unless it already describes it; the title the head has. */
function writeHead(
  path: string,
  options: { notFound?: boolean; entity?: Record<string, unknown> } = {},
): string {
  if (path === headPath) return document.title;
  headPath = path;
  return applySeoMetadata(path, window.location.origin, options).title;
}

const container = document.getElementById('main-content')!;
const pageLifecycle = new AbortController();
// The fetch of the document of a server-rendered page; a new navigation, or an
// action of the explorer on the page still shown, aborts it.
let documentLoad: AbortController | null = null;

function abortDocumentLoad(): void {
  documentLoad?.abort();
  documentLoad = null;
}

const explorer = new ResourceExplorer(
  container,
  (path, entity) => {
    cancelPending();
    abortDocumentLoad();
    const generation = ++navigationGeneration;
    if (window.location.pathname !== path) history.pushState(null, '', path);
    updateActiveNav(getRoute(path).page);
    // Written after the record arrived, from the record: a record address never
    // shows its fallback title ("Films #1") on the way to its real one.
    const title = writeHead(path, { entity });
    requestAnimationFrame(() => {
      if (generation !== navigationGeneration) return;
      window.scrollTo(0, 0);
      container.focus({ preventScroll: true });
      announce(spokenTitle(title));
    });
  },
  () => {
    abortDocumentLoad();
    navigationGeneration++;
  },
);
const webmcp = registerWebMcp(explorer);
void webmcp.ready.then(() => updateWebMcpStatus(container, webmcp.status));

/** Binds the behaviours of a page whose <main> the server drew; /about has none. */
function bindPage(page: Page): void {
  switch (page) {
    case 'home':
      // The live request count fills in on its own; the page does not wait for it.
      void bindHome(container);
      break;
    case 'docs':
      bindDocumentation(container);
      break;
    case 'mcp':
      bindMcp(container);
      break;
  }
}

/**
 * How a navigation started. A link click carries the full URL it left (path,
 * query and fragment), so a failed fetch can put the address back; Back and
 * Forward ('history') never have their history rewritten.
 */
type NavigationSource = { via: 'start' } | { via: 'history' } | { via: 'link'; from: string };

/**
 * A page whose <main> the server draws (2.11.0, slice E1). The first load adopts
 * it as it is: no request, no redraw, no head write, and neither scroll nor
 * focus, so #fragment and #:~:text= work. Without the marker (the server failed
 * to draw it) a static error, never a request or a reload. A later navigation
 * fetches the document and writes the head once, after the swap.
 */
async function showServerPage(
  path: string,
  route: Route,
  first: boolean,
  generation: number,
  source: NavigationSource,
) {
  if (first) {
    if (adoptServerMain(container, route)) {
      bindPage(route.page);
      announce(spokenTitle(document.title));
    } else {
      renderUnavailable(container, path);
    }
    return;
  }

  const controller = new AbortController();
  documentLoad = controller;
  container.setAttribute('aria-busy', 'true');
  const result = await loadServerMain(container, path, route, {
    signal: controller.signal,
    isCurrent: () => generation === navigationGeneration,
    search: window.location.search,
  });
  if (documentLoad === controller) documentLoad = null;
  if (generation !== navigationGeneration) return;
  container.removeAttribute('aria-busy');

  let title: string;
  switch (result) {
    case 'ok':
      title = writeHead(path);
      bindPage(route.page);
      break;
    case 'not-found':
      renderNotFound(container, path);
      title = writeHead(path, { notFound: true });
      break;
    case 'unavailable':
      renderUnavailable(container, path);
      title = writeHead(path);
      break;
    case 'error':
      // The content and the head stay those of the page that was there. After a
      // link the address goes back to the full URL it left; after Back or
      // Forward the history is left alone (rewriting it would lose an entry).
      showLoadFailure(container, path);
      if (source.via === 'link') history.replaceState(null, '', source.from);
      updateActiveNav(getRoute(headPath).page);
      return;
    default:
      // 'reload' (a full load is on its way) or 'aborted' (a newer navigation).
      return;
  }
  window.scrollTo(0, 0);
  container.focus({ preventScroll: true });
  announce(spokenTitle(title));
}

/**
 * A record address (2.12.0, slice E2): the server drew it. The first load adopts
 * it (no request: before 2.12.0 it asked /api) and binds the copy buttons and
 * the JSON colours; without its marker, a static block, never a request. A
 * later navigation is the explorer's sw_get: one request, the document, and the
 * head written once from the record (its commit); no "Loading resource…".
 */
async function showRecord(
  path: string,
  route: Route,
  first: boolean,
  generation: number,
  source: NavigationSource,
) {
  if (first) {
    if (adoptServerMain(container, route)) {
      bindRecord(container);
      announce(spokenTitle(document.title));
    } else {
      renderUnavailable(container, path, 'Resource unavailable');
    }
    return;
  }

  const result = await explorer.execute(
    'sw_get',
    { resource: route.type!.toUpperCase(), id: Number(route.id) },
    { human: true },
  );
  if (generation !== navigationGeneration || result.ok) return;
  if (result.error.code === 'NOT_FOUND') {
    // An id that does not exist, reached by a client-side navigation: the
    // not-found page and head, like the server's 404.
    renderNotFound(container, path);
    const notFoundTitle = writeHead(path, { notFound: true });
    container.focus({ preventScroll: true });
    announce(spokenTitle(notFoundTitle));
    return;
  }
  if (result.error.code === 'SUPERSEDED' || result.error.code === 'CANCELLED') return;
  // The document did not come (5xx, network): the content and the head stay
  // those of the page that was there, with the explorer's alert. After a link
  // the address goes back to the URL it left; Back and Forward are left alone.
  if (source.via === 'link') history.replaceState(null, '', source.from);
  updateActiveNav(getRoute(headPath).page);
}

async function navigate(source: NavigationSource) {
  cancelPending();
  explorer.interrupt();
  abortDocumentLoad();
  container.removeAttribute('aria-busy');
  const generation = ++navigationGeneration;

  const path = window.location.pathname;
  const first = firstNavigation;
  // The route is resolved before the head is touched, and the head is written
  // once: on a server 404 the client never shows "People #9999" with the list's
  // canonical while it waits for a fetch that would fail. A record address
  // writes nothing here: its head comes with the record (the explorer's commit).
  const route = resolveRoute(path);
  updateActiveNav(route.page);

  if (isServerRendered(route)) {
    await showServerPage(path, route, first, generation, source);
    return;
  }

  const title =
    route.page === 'resource-detail'
      ? document.title
      : writeHead(path, { notFound: route.page === 'not-found' });

  switch (route.page) {
    case 'webmcp':
      renderWebMcp(container, webmcp.status);
      break;
    case 'privacy':
      renderPrivacy(container);
      break;
    case 'terms':
      renderTerms(container);
      break;
    case 'stats':
      await renderStats(container, () => generation === navigationGeneration);
      break;
    case 'resource-detail':
      await showRecord(path, route, first, generation, source);
      return;
    case 'resource-list': {
      container.replaceChildren();
      const heading = document.createElement('h1');
      heading.className = 'sw-page-title';
      heading.textContent = 'Loading resource…';
      container.appendChild(heading);
      const result = await explorer.execute(
        'sw_list',
        { resource: route.type!.toUpperCase() },
        { human: true },
      );
      if (generation === navigationGeneration && !result.ok) {
        // The list did not arrive (network, 5xx): the fallback head of its type,
        // so the head no longer describes the page before.
        writeHead(path);
        heading.textContent = 'Resource unavailable';
        if (!container.querySelector('[role="alert"]')) {
          const error = document.createElement('p');
          error.setAttribute('role', 'alert');
          error.textContent = result.error.message;
          container.appendChild(error);
        }
        container.focus({ preventScroll: true });
      }
      return;
    }
    case 'not-found':
      renderNotFound(container, path);
      break;
  }

  // An async render can finish after another navigation: do not steal the new page's focus/announcement
  if (generation !== navigationGeneration) return;

  window.scrollTo(0, 0);
  container.focus({ preventScroll: true });
  announce(spokenTitle(title));
}

// Intercept clicks on internal links to use History API instead of full page reload
document.addEventListener(
  'click',
  (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const anchor = (e.target as Element).closest('a');
    if (!anchor) return;

    const href = anchor.getAttribute('href');
    if (!href) return;

    // Skip external links, anchor links (#), downloads, and links that open in new tabs
    if (
      href.startsWith('http') ||
      href.startsWith('#') ||
      isServerPath(href) ||
      anchor.hasAttribute('target') ||
      anchor.hasAttribute('download')
    )
      return;

    e.preventDefault();
    const from = window.location.pathname + window.location.search + window.location.hash;
    if (href !== window.location.pathname + window.location.search) {
      history.pushState(null, '', href);
    }
    void navigate({ via: 'link', from });
  },
  { signal: pageLifecycle.signal },
);

window.addEventListener('popstate', () => void navigate({ via: 'history' }), {
  signal: pageLifecycle.signal,
});
navigate({ via: 'start' });

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    pageLifecycle.abort();
    navigationGeneration++;
    explorer.dispose();
    webmcp.dispose();
    announcer.remove();
  });
}
