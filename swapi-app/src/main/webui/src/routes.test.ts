import { describe, expect, it } from 'vitest';
import { getRoute, takeServerStatus } from './routes';

describe('getRoute', () => {
  // Exact, like SeoRoutes on the server: what the server answers with 404, the
  // client shows as the not-found page (2.9.1).
  it.each([
    ['/', { page: 'home' }],
    ['/docs', { page: 'docs' }],
    ['/docs/', { page: 'docs' }],
    ['/docs/mcp', { page: 'mcp' }],
    ['/docs/webmcp', { page: 'webmcp' }],
    ['/faq', { page: 'faq' }],
    ['/about', { page: 'about' }],
    ['/privacy', { page: 'privacy' }],
    ['/terms', { page: 'terms' }],
    ['/stats', { page: 'stats' }],
    ['/resource/people', { page: 'resource-list', type: 'people' }],
    ['/resource/vehicles', { page: 'resource-list', type: 'vehicles' }],
    ['/resource/people/1', { page: 'resource-detail', type: 'people', id: '1' }],
    // Trailing slash: 200 on both sides.
    ['/resource/people/', { page: 'resource-list', type: 'people' }],
    ['/resource/people/1/', { page: 'resource-detail', type: 'people', id: '1' }],
  ])('%s is a page', (path, route) => {
    expect(getRoute(path)).toEqual(route);
  });

  it.each([
    '/docs/nope',
    '/faq/x',
    '/about/x',
    '/privacy/x',
    '/stats/x',
    '/resource/droids',
    '/resource/droids/1',
    '/resource/people/01',
    '/resource/people/abc',
    '/resource/people/%31',
    '/resource/people/1234567890',
    '/resource/people/1/x',
    '/resource',
    '/qualquer',
    '/index.html',
  ])('%s is not found', (path) => {
    expect(getRoute(path)).toEqual({ page: 'not-found' });
  });
});

describe('takeServerStatus', () => {
  it('reads the 404 marker the server put in the head and removes it', () => {
    const doc = document.implementation.createHTMLDocument('x');
    const meta = doc.createElement('meta');
    meta.name = 'swapi:status';
    meta.content = '404';
    doc.head.appendChild(meta);

    expect(takeServerStatus(doc)).toBe(404);
    expect(doc.querySelector('meta[name="swapi:status"]')).toBeNull();
    expect(takeServerStatus(doc)).toBeNull();
  });

  it('is null without the marker', () => {
    const doc = document.implementation.createHTMLDocument('x');
    expect(takeServerStatus(doc)).toBeNull();
  });
});
