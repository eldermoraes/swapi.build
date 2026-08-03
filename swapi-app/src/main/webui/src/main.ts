import './style.css';
import { renderHome } from './pages/home';
import { renderResourceList, renderResourceDetail } from './pages/resource';
import { renderDocumentation } from './pages/documentation';
import { renderAbout } from './pages/about';
import { renderMcp } from './pages/mcp';
import { renderPrivacy } from './pages/privacy';
import { renderTerms } from './pages/terms';
import { cancelPending } from './api';
import { getResourceMeta } from './constants';

const announcer = document.createElement('div');
announcer.setAttribute('aria-live', 'polite');
announcer.setAttribute('aria-atomic', 'true');
announcer.className = 'sr-only';
document.body.appendChild(announcer);

function announce(message: string) {
  announcer.textContent = '';
  requestAnimationFrame(() => {
    announcer.textContent = message;
  });
}

function getRoute(): { page: string; type?: string; id?: string } {
  const path = window.location.pathname;
  const parts = path.split('/').filter(Boolean);

  if (parts.length === 0) return { page: 'home' };
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

function getPageTitle(route: { page: string; type?: string; id?: string }): string {
  switch (route.page) {
    case 'home':
      return 'SWAPI - The Star Wars API';
    case 'docs':
      return 'Documentation - SWAPI';
    case 'mcp':
      return 'MCP Server - SWAPI';
    case 'about':
      return 'About - SWAPI';
    case 'privacy':
      return 'Privacy Policy - SWAPI';
    case 'terms':
      return 'Terms of Use - SWAPI';
    case 'resource-list':
      return `${getResourceMeta(route.type!).title} - SWAPI`;
    case 'resource-detail':
      return `${getResourceMeta(route.type!).title} #${route.id} - SWAPI`;
    default:
      return 'SWAPI - The Star Wars API';
  }
}

function updateActiveNav() {
  const route = getRoute();
  document.querySelectorAll('.nav-link').forEach((link) => {
    link.classList.remove('active');
    const href = link.getAttribute('href') || '';
    if (route.page === 'home' && href === '/') link.classList.add('active');
    if (route.page === 'docs' && href === '/docs') link.classList.add('active');
    if (route.page === 'mcp' && href === '/docs/mcp') link.classList.add('active');
    if (route.page === 'about' && href === '/about') link.classList.add('active');
  });
}

async function navigate() {
  cancelPending();

  const container = document.getElementById('main-content')!;
  const route = getRoute();
  const path = window.location.pathname;
  updateActiveNav();

  const title = getPageTitle(route);
  document.title = title;

  switch (route.page) {
    case 'home':
      renderHome(container);
      break;
    case 'docs':
      await renderDocumentation(container);
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
      await renderResourceList(container, route.type!);
      break;
    case 'resource-detail':
      await renderResourceDetail(container, route.type!, route.id!);
      break;
    default:
      renderHome(container);
  }

  // Render assíncrono pode terminar depois de outra navegação: não roubar foco/anúncio da página nova
  if (window.location.pathname !== path) return;

  window.scrollTo(0, 0);
  container.focus();
  announce(title.replace(' - SWAPI', ''));
}

// Intercept clicks on internal links to use History API instead of full page reload
document.addEventListener('click', (e) => {
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
    navigate();
  }
});

window.addEventListener('popstate', navigate);
navigate();
