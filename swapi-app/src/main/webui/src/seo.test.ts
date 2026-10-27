import { beforeEach, describe, expect, it } from 'vitest';
import { applySeoMetadata, getSeoMetadata, PUBLIC_SEO_ROUTES, spokenTitle } from './seo';

const origin = 'https://preview.example';

describe('SEO metadata', () => {
  beforeEach(() => {
    document.head.innerHTML = `
      <title>old</title>
      <meta name="description" content="old">
    `;
  });

  it('returns accurate metadata for public routes', () => {
    expect(getSeoMetadata('/').title).toBe(
      'SWAPI alternative: free Star Wars API and MCP server | swapi.build',
    );
    expect(getSeoMetadata('/docs').title).toBe(
      'SWAPI docs: Star Wars REST API endpoints and OpenAPI | swapi.build',
    );
    expect(getSeoMetadata('/docs/mcp').title).toBe(
      'Star Wars MCP server: remote, no API key | swapi.build',
    );
    expect(getSeoMetadata('/about').title).toBe(
      'About: a SWAPI alternative built with Quarkus | swapi.build',
    );
    expect(getSeoMetadata('/resource/people').title).toBe(
      'Star Wars People API: JSON data | swapi.build',
    );
    expect(getSeoMetadata('/stats').title).toBe('Usage stats | swapi.build');
    expect(getSeoMetadata('/privacy').title).toBe('Privacy Policy | swapi.build');
    expect(getSeoMetadata('/terms').title).toBe('Terms of Use | swapi.build');
  });

  it('uses the singular noun as the modifier in resource descriptions', () => {
    expect(getSeoMetadata('/resource/films').description).toBe(
      'Browse Star Wars film records as JSON from the free swapi.build REST API and MCP server.',
    );
    expect(getSeoMetadata('/resource/starships').description).toContain(
      'Star Wars starship records',
    );
    expect(getSeoMetadata('/resource/people').description).toContain('Star Wars people records');
    expect(getSeoMetadata('/resource/species').description).toContain('Star Wars species records');
    expect(getSeoMetadata('/resource/films/3').description).toBe(
      'Star Wars film record #3 from the swapi.build REST API.',
    );
  });

  it('speaks the title without the brand suffix', () => {
    expect(spokenTitle('Usage stats | swapi.build')).toBe('Usage stats');
  });

  // 2.9.1: the server answers these with 404, so the client says the same thing.
  it('gives unknown paths and invalid ids the not-found metadata, without a canonical', () => {
    for (const path of [
      '/not-a-real-page?x=1#frag',
      '/docs/nope',
      '/resource/people/01',
      '/resource/droids/1',
    ]) {
      const meta = getSeoMetadata(path);
      expect(meta.title).toBe('Page not found | swapi.build');
      expect(meta.canonicalPath).toBeNull();
      expect(meta.robots).toBe('noindex');
    }
  });

  it('on a 404 sets title, description and robots only, and drops canonical and og:url', () => {
    document.head.innerHTML = `
      <title>old</title>
      <meta name="description" content="old">
      <link rel="canonical" href="${origin}/docs">
      <meta property="og:url" content="${origin}/docs">
    `;
    const meta = applySeoMetadata('/nao-existe', origin);

    expect(meta.title).toBe('Page not found | swapi.build');
    expect(document.title).toBe('Page not found | swapi.build');
    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex');
    expect(document.querySelector('meta[name="description"]')?.getAttribute('content')).toContain(
      'does not exist',
    );
    expect(document.querySelector('link[rel="canonical"]')).toBeNull();
    expect(document.querySelector('meta[property^="og:"]')).toBeNull();
    expect(document.querySelector('meta[name^="twitter:"]')).toBeNull();

    applySeoMetadata('/docs', origin);
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${origin}/docs`,
    );
    expect(document.querySelector('meta[property="og:url"]')?.getAttribute('content')).toBe(
      `${origin}/docs`,
    );
    expect(document.querySelector('meta[property="og:title"]')).not.toBeNull();
  });

  // A detail whose entity does not exist (the server marked it, or the API said
  // 404) looks valid to the client: the caller says so.
  it('applies the not-found metadata when the caller says the page is a 404', () => {
    const meta = applySeoMetadata('/resource/people/9999', origin, { notFound: true });
    expect(meta.title).toBe('Page not found | swapi.build');
    expect(document.title).toBe('Page not found | swapi.build');
    expect(document.querySelector('link[rel="canonical"]')).toBeNull();
  });

  it('applies document title, canonical, social cards, description and robots tags', () => {
    applySeoMetadata('/docs?ignored=1#section', origin);

    expect(document.title).toBe(
      'SWAPI docs: Star Wars REST API endpoints and OpenAPI | swapi.build',
    );
    expect(document.querySelector('meta[property="og:site_name"]')?.getAttribute('content')).toBe(
      'swapi.build',
    );
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
    title: 'Star Wars WebMCP tools in the browser | swapi.build',
    robots: 'index,follow',
  });
});
