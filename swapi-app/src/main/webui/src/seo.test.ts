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

  it('speaks the title without the brand suffix', () => {
    expect(spokenTitle('Usage stats | swapi.build')).toBe('Usage stats');
  });

  it('marks unknown document paths noindex and canonicalizes them to home', () => {
    const meta = getSeoMetadata('/not-a-real-page?x=1#frag');
    expect(meta.title).toBe('SWAPI alternative: free Star Wars API and MCP server | swapi.build');
    expect(meta.canonicalPath).toBe('/');
    expect(meta.robots).toBe('noindex,follow');
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
