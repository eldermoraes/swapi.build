import { describe, it, expect } from 'vitest';
import indexHtml from '../index.html?raw';
import { activeNavHref } from './nav';

function menuLinks(): HTMLAnchorElement[] {
  const doc = new DOMParser().parseFromString(indexHtml, 'text/html');
  return [...doc.querySelectorAll<HTMLAnchorElement>('nav.sw-menu a.nav-link')];
}

describe('main navigation', () => {
  it('shows a Stats link to /stats right after Documentation, styled like the others', () => {
    const links = menuLinks();
    const labels = links.map((a) => a.textContent!.trim());
    const docs = labels.indexOf('Documentation');
    expect(docs).toBeGreaterThanOrEqual(0);
    expect(labels[docs + 1]).toBe('Stats');
    const stats = links[docs + 1];
    expect(stats.getAttribute('href')).toBe('/stats');
    expect(stats.className).toBe('nav-link');
    // An internal link: no new tab, so the SPA router handles it.
    expect(stats.hasAttribute('target')).toBe(false);
  });

  it('marks Stats as the active item on /stats', () => {
    expect(activeNavHref('stats')).toBe('/stats');
  });

  it('keeps the active item of the other pages', () => {
    expect(activeNavHref('home')).toBe('/');
    expect(activeNavHref('docs')).toBe('/docs');
    expect(activeNavHref('webmcp')).toBe('/docs');
    expect(activeNavHref('mcp')).toBe('/docs/mcp');
    expect(activeNavHref('about')).toBe('/about');
    expect(activeNavHref('privacy')).toBeNull();
    expect(activeNavHref('resource-list')).toBeNull();
  });
});
