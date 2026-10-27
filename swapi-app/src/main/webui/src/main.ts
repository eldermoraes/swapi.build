import './style.css';
import { renderHome } from './pages/home';
import { ResourceExplorer } from './resource-actions';
import { registerWebMcp } from './webmcp';
import { renderWebMcp, updateWebMcpStatus } from './pages/webmcp';
import { renderDocumentation } from './pages/documentation';
import { renderAbout } from './pages/about';
import { renderMcp } from './pages/mcp';
import { renderPrivacy } from './pages/privacy';
import { renderTerms } from './pages/terms';
import { renderStats } from './pages/stats';
import { renderNotFound } from './pages/not-found';
import { isServerPath } from './spa-links';
import { cancelPending } from './api';
import { applySeoMetadata, spokenTitle } from './seo';
import { activeNavHref } from './nav';
import { getRoute, takeServerStatus, type Page, type Route } from './routes';

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

const container = document.getElementById('main-content')!;
const pageLifecycle = new AbortController();
const explorer = new ResourceExplorer(
  container,
  (path) => {
    cancelPending();
    const generation = ++navigationGeneration;
    if (window.location.pathname !== path) history.pushState(null, '', path);
    updateActiveNav(getRoute(path).page);
    const title = applySeoMetadata(path, window.location.origin).title;
    requestAnimationFrame(() => {
      if (generation !== navigationGeneration) return;
      window.scrollTo(0, 0);
      container.focus({ preventScroll: true });
      announce(spokenTitle(title));
    });
  },
  () => {
    navigationGeneration++;
  },
);
const webmcp = registerWebMcp(explorer);
void webmcp.ready.then(() => updateWebMcpStatus(container, webmcp.status));

async function navigate() {
  cancelPending();
  explorer.interrupt();
  const generation = ++navigationGeneration;

  const path = window.location.pathname;
  // The route is resolved before the head is touched, and the head is written
  // once: on a server 404 the client never shows "People #9999" with the list's
  // canonical while it waits for a fetch that would fail.
  const route = resolveRoute(path);
  updateActiveNav(route.page);

  const title = applySeoMetadata(path, window.location.origin, {
    notFound: route.page === 'not-found',
  }).title;

  switch (route.page) {
    case 'home':
      // The live request count fills in on its own; the page does not wait for it.
      void renderHome(container);
      break;
    case 'docs':
      await renderDocumentation(container, () => generation === navigationGeneration);
      break;
    case 'webmcp':
      renderWebMcp(container, webmcp.status);
      break;
    case 'mcp':
      renderMcp(container);
      break;
    case 'about':
      renderAbout(container);
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
    case 'resource-list':
    case 'resource-detail': {
      container.replaceChildren();
      const heading = document.createElement('h1');
      heading.className = 'sw-page-title';
      heading.textContent = 'Loading resource…';
      container.appendChild(heading);
      const result = await explorer.execute(
        route.page === 'resource-list' ? 'sw_list' : 'sw_get',
        {
          resource: route.type!.toUpperCase(),
          ...(route.page === 'resource-detail' ? { id: Number(route.id) } : {}),
        },
        { human: true },
      );
      // An id that does not exist, reached by a client-side navigation: the
      // not-found page and head, like the server's 404.
      if (generation === navigationGeneration && !result.ok && result.error.code === 'NOT_FOUND') {
        renderNotFound(container, path);
        const notFoundTitle = applySeoMetadata(path, window.location.origin, {
          notFound: true,
        }).title;
        container.focus({ preventScroll: true });
        announce(spokenTitle(notFoundTitle));
        return;
      }
      if (generation === navigationGeneration && !result.ok) {
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
    if (href !== window.location.pathname + window.location.search) {
      history.pushState(null, '', href);
    }
    void navigate();
  },
  { signal: pageLifecycle.signal },
);

window.addEventListener('popstate', navigate, { signal: pageLifecycle.signal });
navigate();

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    pageLifecycle.abort();
    navigationGeneration++;
    explorer.dispose();
    webmcp.dispose();
    announcer.remove();
  });
}
