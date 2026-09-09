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
import { cancelPending } from './api';
import { applySeoMetadata } from './seo';
import { inject } from '@vercel/analytics';
import { injectSpeedInsights } from '@vercel/speed-insights';

// Both scripts are served by the Vercel edge at /_vercel/*. In dev, Quinoa
// serves Vite and those paths do not exist, hence the explicit mode: without
// it the scripts would try to load from localhost:5173 and fail in the console.
// The edge only intercepts /_vercel/* when the features are enabled on the
// project — otherwise Quinoa's SPA fallback returns index.html with 200,
// and collection fails silently. See docs/DEPLOY.md.
const analyticsMode = import.meta.env.PROD ? 'production' : 'development';
inject({ mode: analyticsMode });
injectSpeedInsights();

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

function getRoute(): { page: string; type?: string; id?: string } {
  const path = window.location.pathname;
  const parts = path.split('/').filter(Boolean);

  if (parts.length === 0) return { page: 'home' };
  if (parts[0] === 'docs' && parts[1] === 'webmcp') return { page: 'webmcp' };
  if (parts[0] === 'docs' && parts[1] === 'mcp') return { page: 'mcp' };
  if (parts[0] === 'docs') return { page: 'docs' };
  if (parts[0] === 'about') return { page: 'about' };
  if (parts[0] === 'privacy') return { page: 'privacy' };
  if (parts[0] === 'terms') return { page: 'terms' };
  if (parts[0] === 'resource' && parts.length === 2)
    return { page: 'resource-list', type: parts[1] };
  if (parts[0] === 'resource' && parts.length === 3)
    return { page: 'resource-detail', type: parts[1], id: parts[2] };
  return { page: 'home' };
}

function updateActiveNav() {
  const route = getRoute();
  document.querySelectorAll('.nav-link').forEach((link) => {
    link.classList.remove('active');
    const href = link.getAttribute('href') || '';
    if (route.page === 'home' && href === '/') link.classList.add('active');
    if ((route.page === 'docs' || route.page === 'webmcp') && href === '/docs')
      link.classList.add('active');
    if (route.page === 'mcp' && href === '/docs/mcp') link.classList.add('active');
    if (route.page === 'about' && href === '/about') link.classList.add('active');
  });
}

const container = document.getElementById('main-content')!;
const pageLifecycle = new AbortController();
const explorer = new ResourceExplorer(
  container,
  (path) => {
    cancelPending();
    const generation = ++navigationGeneration;
    if (window.location.pathname !== path) history.pushState(null, '', path);
    updateActiveNav();
    const title = applySeoMetadata(path, window.location.origin).title;
    requestAnimationFrame(() => {
      if (generation !== navigationGeneration) return;
      window.scrollTo(0, 0);
      container.focus({ preventScroll: true });
      announce(title.replace(' - SWAPI', ''));
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

  const route = getRoute();
  const path = window.location.pathname;
  updateActiveNav();

  const title = applySeoMetadata(path, window.location.origin).title;

  switch (route.page) {
    case 'home':
      renderHome(container);
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
    default:
      renderHome(container);
  }

  // An async render can finish after another navigation: do not steal the new page's focus/announcement
  if (generation !== navigationGeneration) return;

  window.scrollTo(0, 0);
  container.focus({ preventScroll: true });
  announce(title.replace(' - SWAPI', ''));
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
      anchor.hasAttribute('target') ||
      anchor.hasAttribute('download')
    )
      return;

    e.preventDefault();
    if (href !== window.location.pathname) {
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
