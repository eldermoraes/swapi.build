import { beforeEach, describe, expect, it } from 'vitest';
import { applySeoMetadata, getSeoMetadata, PUBLIC_SEO_ROUTES } from './seo';

const origin = 'https://preview.example';

describe('SEO metadata', () => {
  beforeEach(() => {
    document.head.innerHTML = `
      <title>old</title>
      <meta name="description" content="old">
    `;
  });

  it('returns accurate metadata for public routes', () => {
    expect(getSeoMetadata('/').title).toBe('SWAPI - The Star Wars API');
    expect(getSeoMetadata('/docs').title).toBe('Documentation - SWAPI');
    expect(getSeoMetadata('/docs/mcp').title).toBe('MCP Server - SWAPI');
    expect(getSeoMetadata('/about').title).toBe('About - SWAPI');
    expect(getSeoMetadata('/resource/people').title).toBe('People - SWAPI');
    expect(getSeoMetadata('/privacy').title).toBe('Privacy Policy - SWAPI');
    expect(getSeoMetadata('/terms').title).toBe('Terms of Use - SWAPI');
  });

  it('marks unknown document paths noindex and canonicalizes them to home', () => {
    const meta = getSeoMetadata('/not-a-real-page?x=1#frag');
    expect(meta.title).toBe('SWAPI - The Star Wars API');
    expect(meta.canonicalPath).toBe('/');
    expect(meta.robots).toBe('noindex,follow');
  });

  it('applies document title, canonical, social cards, description and robots tags', () => {
    applySeoMetadata('/docs?ignored=1#section', origin);

    expect(document.title).toBe('Documentation - SWAPI');
    expect(document.querySelector('meta[name="description"]')?.getAttribute('content')).toContain(
      'OpenAPI',
    );
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${origin}/docs`,
    );
    expect(document.querySelector('meta[property="og:url"]')?.getAttribute('content')).toBe(
      `${origin}/docs`,
    );
    expect(document.querySelector('meta[property="og:image"]')?.getAttribute('content')).toBe(
      `${origin}/og-image.png`,
    );
    expect(document.querySelector('meta[name="twitter:card"]')?.getAttribute('content')).toBe(
      'summary_large_image',
    );
    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe(
      'index,follow',
    );
  });

  it('keeps route metadata relative and builds absolute URLs from the runtime origin', () => {
    for (const route of PUBLIC_SEO_ROUTES) {
      expect(route.canonicalPath.startsWith('/')).toBe(true);
      expect(route.canonicalPath).not.toContain('https://swapi.build');
      expect(route.description).not.toContain('https://swapi.build');
    }

    applySeoMetadata('/about', 'https://alt.example');
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      'https://alt.example/about',
    );
  });
});

it('gives the WebMCP guide an indexable canonical route', () => {
  expect(getSeoMetadata('/docs/webmcp')).toMatchObject({
    canonicalPath: '/docs/webmcp',
    title: 'WebMCP in the browser - SWAPI',
    robots: 'index,follow',
  });
});
