import './style.css';
import { bindHome } from './pages/home';
import { ResourceExplorer, type ActionResult } from './resource-actions';
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

/** What a search announces: the number of records it found. */
function resultCount(found: number): string {
  if (found === 0) return 'No results found.';
  return found === 1 ? '1 result found.' : `${found} results found.`;
}

function updateActiveNav(page: Page) {
  const active = activeNavHref(page);
  document.querySelectorAll('.nav-link').forEach((link) => {
    const isActive = active !== null && link.getAttribute('href') === active;
    link.classList.toggle('active', isActive);
    // The underline is for the eye; this says "current page" to a screen reader.
    if (isActive) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
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

// The height of the sticky header, for the scroll padding of the root
// (base.css): a #fragment target lands below the header, not under it.
const header = document.querySelector('.sw-header');
function syncHeaderHeight(): void {
  if (!header) return;
  const height = Math.ceil(header.getBoundingClientRect().height);
  document.documentElement.style.setProperty('--sw-header-height', `${height}px`);
}
syncHeaderHeight();
const headerResize =
  header && typeof ResizeObserver === 'function' ? new ResizeObserver(syncHeaderHeight) : null;
if (header) headerResize?.observe(header);

// The document (path and query) the page shows (2.15.0). A link or a Back and
// Forward that only changes the #fragment does not fetch it again: the page
// scrolls to the fragment, or goes back to where the reader was on that entry.
let shownDocument: string | null = window.location.pathname + window.location.search;

// Back and Forward put the reader where they were (issue #71). The browser
// cannot do it here: it restores the scroll when the address changes, and the
// page of that address arrives later, over a page of another height. So the
// client restores it, per history entry, once the page is on screen. It takes
// the job from the browser only when the reader first leaves a page
// (`rememberScroll`), never while the document loads: after a reload the
// browser restores the scroll itself, as the page grows, and "manual" set at
// the start stopped it half way (seen in Chromium). The positions live in
// memory, so the browser gets the job back when the page is left.
window.addEventListener('pagehide', () => (history.scrollRestoration = 'auto'), {
  signal: pageLifecycle.signal,
});

const scrollPositions = new Map<string, { left: number; top: number }>();
// Keys of this load never match the ones an earlier load left in the history.
const entryKeyPrefix = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
let entriesPushed = 0;

/** The state of an entry the client pushes: its key in `scrollPositions`. */
function newEntryState(): { swEntry: string } {
  return { swEntry: `${entryKeyPrefix}-${++entriesPushed}` };
}

/** The key of the entry the address is on; an entry the client did not push has its URL for key. */
function entryKey(): string {
  const state: unknown = history.state;
  const key = state && typeof state === 'object' ? (state as { swEntry?: unknown }).swEntry : null;
  if (typeof key === 'string') return key;
  return `url:${window.location.pathname}${window.location.search}${window.location.hash}`;
}

// The entry whose page is on screen; null while the address is not the page
// shown (a navigation waits for its page, or did not show it).
let shownEntry: string | null = entryKey();

/**
 * The reader leaves the entry of the address: keeps where they are, for the
 * entry whose page is on screen. From here on the client restores the scroll
 * of this entry, and of the entries pushed from it, which take its mode.
 */
function rememberScroll(): void {
  history.scrollRestoration = 'manual';
  if (shownEntry === null) return;
  scrollPositions.set(shownEntry, { left: window.scrollX, top: window.scrollY });
}

/** The address moved to another entry of the page on screen (only the #fragment changed). */
function followFragmentEntry(): void {
  if (shownEntry !== null) shownEntry = entryKey();
}

/** Puts the reader where they were on the entry of the address; false when nothing was kept. */
function restoreScroll(): boolean {
  const kept = scrollPositions.get(entryKey());
  if (!kept) return false;
  // At once: the smooth scroll of the stylesheet is for jumps inside a page.
  window.scrollTo({ ...kept, behavior: 'instant' });
  return true;
}

/**
 * The page of the address is on screen: after Back or Forward the reader is
 * where they were on that entry; otherwise at its #fragment (when `fragment`),
 * else at the top.
 */
function placeScroll(source: NavigationSource | null, fragment = false): void {
  shownEntry = entryKey();
  if (source?.via === 'history' && restoreScroll()) return;
  if (fragment && scrollToFragment()) return;
  window.scrollTo(0, 0);
}

/** Scrolls to the element the #fragment of the address names; false when there is none. */
function scrollToFragment(): boolean {
  let id: string;
  try {
    id = decodeURIComponent(window.location.hash.slice(1));
  } catch {
    return false;
  }
  if (!id) return false;
  const target = document.getElementById(id);
  if (!target || !container.contains(target)) return false;
  target.scrollIntoView();
  return true;
}
// The fetch of the document of a server-rendered page; a new navigation, or an
// action of the explorer on the page still shown, aborts it.
let documentLoad: AbortController | null = null;

function abortDocumentLoad(): void {
  documentLoad?.abort();
  documentLoad = null;
}

const explorer = new ResourceExplorer(
  container,
  (path, entity, found) => {
    cancelPending();
    abortDocumentLoad();
    const generation = ++navigationGeneration;
    const arrival = explorerNavigation;
    if (window.location.pathname !== path) {
      rememberScroll();
      history.pushState(newEntryState(), '', path);
    }
    // The explorer's page is the document shown, also when it opened it on its
    // own (Random, a WebMCP tool): Back to the address before is a navigation.
    shownDocument = window.location.pathname + window.location.search;
    shownEntry = entryKey();
    updateActiveNav(getRoute(path).page);
    // Written after the record arrived, from the record: a record address never
    // shows its fallback title ("Films #1") on the way to its real one.
    const title = writeHead(path, { entity });
    if (found !== undefined) {
      // A search on the list shown: the page is the same, so the reader stays
      // where they are (scroll, focus) and hears the news, the count.
      announce(resultCount(found));
      return;
    }
    requestAnimationFrame(() => {
      if (generation !== navigationGeneration) return;
      placeScroll(arrival);
      container.focus({ preventScroll: true });
      announce(spokenTitle(title));
    });
  },
  () => {
    abortDocumentLoad();
    // The reader acted on the page still shown: the navigation that waited for
    // its page will not show it.
    const interrupted = waitingNavigation();
    if (interrupted) keepShownPage(interrupted);
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
 * Forward ('history') never have their history rewritten. `fromShown` is false
 * when that URL was not the page on screen: the click came while a Back or
 * Forward still waited for its page.
 */
type NavigationSource =
  | { via: 'start' }
  | { via: 'history' }
  | { via: 'link'; from: string; fromShown: boolean };

// The navigation that waits for its page (the document of a server-rendered
// page or of a record) while the page before is still on screen.
let waiting: { source: NavigationSource; generation: number } | null = null;

function waitingNavigation(): NavigationSource | null {
  return waiting?.generation === navigationGeneration ? waiting.source : null;
}

/** Runs the wait of a navigation for its page, as the waiting one while it lasts. */
async function whileWaiting<T>(
  source: NavigationSource,
  generation: number,
  wait: () => Promise<T>,
): Promise<T> {
  const entry = { source, generation };
  waiting = entry;
  try {
    return await wait();
  } finally {
    if (waiting === entry) waiting = null;
  }
}

/**
 * A navigation did not show its page (it failed, or the reader acted on the
 * page still shown): the content and the head stay those of the page that was
 * there. After a link the address goes back to the full URL it left, which is
 * again the document shown (unless a Back or Forward had moved the address
 * first); after Back or Forward the history is left alone (rewriting it would
 * lose an entry). When the address is not the page shown, no document is taken
 * for the one shown, so the next navigation fetches whatever it names.
 */
function keepShownPage(source: NavigationSource): void {
  updateActiveNav(getRoute(headPath).page);
  if (source.via === 'link') {
    // The entry keeps its state: it is now an entry of the page shown, and the
    // reader's place on it is kept under its key when they leave.
    history.replaceState(history.state, '', source.from);
    if (source.fromShown) {
      shownDocument = window.location.pathname + window.location.search;
      shownEntry = entryKey();
      return;
    }
  }
  shownDocument = null;
  shownEntry = null;
}

// The navigation the explorer is opening a page for, so its commit places the
// scroll as that navigation asks (Back and Forward: where the reader was).
let explorerNavigation: NavigationSource | null = null;

/** Runs the explorer for a navigation: the list or the record of the address. */
async function openWithExplorer(
  source: NavigationSource,
  tool: 'sw_list' | 'sw_get',
  args: { resource: string; id?: number },
): Promise<ActionResult> {
  explorerNavigation = source;
  try {
    return await explorer.execute(tool, args, { human: true });
  } finally {
    if (explorerNavigation === source) explorerNavigation = null;
  }
}

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
  const result = await whileWaiting(source, generation, () =>
    loadServerMain(container, path, route, {
      signal: controller.signal,
      isCurrent: () => generation === navigationGeneration,
      search: window.location.search,
    }),
  );
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
      showLoadFailure(container, path);
      keepShownPage(source);
      return;
    default:
      // 'reload' (a full load is on its way) or 'aborted' (a newer navigation).
      return;
  }
  // A link to /faq#compare lands on the comparison, not on the top (2.15.0).
  placeScroll(source, result === 'ok');
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

  const result = await whileWaiting(source, generation, () =>
    openWithExplorer(source, 'sw_get', {
      resource: route.type!.toUpperCase(),
      id: Number(route.id),
    }),
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
  // The document did not come (5xx, network): the explorer's alert is on the
  // page that was there.
  keepShownPage(source);
}

async function navigate(source: NavigationSource) {
  cancelPending();
  explorer.interrupt();
  abortDocumentLoad();
  container.removeAttribute('aria-busy');
  const generation = ++navigationGeneration;

  const path = window.location.pathname;
  shownDocument = path + window.location.search;
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
      const result = await openWithExplorer(source, 'sw_list', {
        resource: route.type!.toUpperCase(),
      });
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

  placeScroll(source);
  container.focus({ preventScroll: true });
  announce(spokenTitle(title));
}

/** The href of the link navigation still running, if any (see the click handler). */
let linkInFlight: string | null = null;

// Intercept clicks on internal links to use History API instead of full page reload
document.addEventListener(
  'click',
  (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const anchor = (e.target as Element).closest('a');
    if (!anchor) return;

    const href = anchor.getAttribute('href');
    if (!href) return;
    // A "#…" link is the browser's (the skip link moves the focus too): it adds
    // an entry and scrolls. The reader's place before the jump is kept here,
    // and the hashchange listener below follows the new entry.
    if (href.startsWith('#')) rememberScroll();

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
    // Only the #fragment changes (2.15.0): the page shown stays, no request, no
    // redraw; the address takes the fragment and the page scrolls to it.
    const target = new URL(href, window.location.href);
    if (target.hash && target.pathname + target.search === shownDocument) {
      if (target.hash !== window.location.hash) {
        rememberScroll();
        history.pushState(newEntryState(), '', target.hash);
        followFragmentEntry();
      }
      scrollToFragment();
      return;
    }
    // A second click on the link whose navigation is still running (2.12.0
    // review): ignored. Taken as a new navigation, its `from` would be the
    // address the first click pushed, and a failure would put that address
    // back over the content that stayed: the URL and the page would disagree.
    if (linkInFlight === href) return;
    // A click while another link's navigation waits for its page: the page
    // shown is still the one that link left, so a failure goes back there.
    // While a Back or Forward waits, the address is already not the page shown.
    const pending = waitingNavigation();
    const from =
      pending?.via === 'link'
        ? pending.from
        : window.location.pathname + window.location.search + window.location.hash;
    const fromShown = pending?.via === 'link' ? pending.fromShown : pending === null;
    // The reader leaves the page on screen: their place on it is kept for Back.
    rememberScroll();
    shownEntry = null;
    if (href !== window.location.pathname + window.location.search) {
      history.pushState(newEntryState(), '', href);
    }
    linkInFlight = href;
    void navigate({ via: 'link', from, fromShown }).finally(() => {
      if (linkInFlight === href) linkInFlight = null;
    });
  },
  { signal: pageLifecycle.signal },
);

window.addEventListener(
  'popstate',
  () => {
    // An entry the client never left is still the browser's to restore (the
    // address bar made it): its scroll is left alone below.
    const browserRestores = history.scrollRestoration === 'auto';
    // The reader left the page on screen: their place on it is kept.
    rememberScroll();
    // Back or Forward between two fragments of the page shown (2.15.0): nothing
    // to fetch or redraw; the reader is where they were on that entry. When
    // nothing was kept for it (a reload came between), at its fragment, and
    // without one at the top.
    if (window.location.pathname + window.location.search === shownDocument) {
      followFragmentEntry();
      if (restoreScroll() || scrollToFragment() || browserRestores) return;
      if (!window.location.hash) window.scrollTo(0, 0);
      return;
    }
    shownEntry = null;
    void navigate({ via: 'history' });
  },
  {
    signal: pageLifecycle.signal,
  },
);
// The browser moved the address to another fragment of the page on screen (a
// "#…" link, the address bar): what the reader scrolls from here on belongs to
// that entry. After Back and Forward, popstate has already done the same.
window.addEventListener('hashchange', followFragmentEntry, { signal: pageLifecycle.signal });
navigate({ via: 'start' });

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    pageLifecycle.abort();
    headerResize?.disconnect();
    navigationGeneration++;
    explorer.dispose();
    webmcp.dispose();
    announcer.remove();
  });
}
