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

// 2.10.0: the record addresses, from seo/site.json.
describe('record addresses', () => {
  const film = {
    title: 'A New Hope',
    episode_id: 4,
    director: 'George Lucas',
    release_date: '1977-05-25',
    url: 'https://swapi.build/api/films/1',
  };
  const luke = { name: 'Luke Skywalker', url: 'https://swapi.build/api/people/1' };

  it('a film is indexable, with its own canonical and a title with its name', () => {
    expect(getSeoMetadata('/resource/films/1', film)).toEqual({
      path: '/resource/films/1',
      canonicalPath: '/resource/films/1',
      title: 'A New Hope — Star Wars film JSON | swapi.build',
      description:
        'A New Hope, Star Wars Episode 4, directed by George Lucas and released 1977-05-25. Its JSON record from the free swapi.build REST API and MCP server.',
      robots: 'index,follow',
    });
  });

  it('a person has the name in the title, but stays noindex with the list canonical', () => {
    expect(getSeoMetadata('/resource/people/1', luke)).toEqual({
      path: '/resource/people/1',
      canonicalPath: '/resource/people',
      title: 'Luke Skywalker — Star Wars character JSON | swapi.build',
      description: 'Star Wars people record #1 from the swapi.build REST API.',
      robots: 'noindex,follow',
    });
  });

  it('without the record: the fallback title, with the robots and canonical of the type', () => {
    expect(getSeoMetadata('/resource/films/3')).toMatchObject({
      title: 'Films #3 | swapi.build',
      description: 'Star Wars film record #3 from the swapi.build REST API.',
      robots: 'index,follow',
      canonicalPath: '/resource/films/3',
    });
    expect(getSeoMetadata('/resource/people/1').title).toBe('People #1 | swapi.build');
  });

  it('a record with an empty field gets the fallback, never "undefined"', () => {
    expect(getSeoMetadata('/resource/films/8', { episode_id: 8 }).title).toBe(
      'Films #8 | swapi.build',
    );
  });

  // A record of an enabled type that the code has never seen is indexable by the
  // rule of its type, with no code change.
  it('a new film is indexable by the rule of its type', () => {
    expect(
      getSeoMetadata('/resource/films/7', {
        title: 'The Force Awakens',
        episode_id: 7,
        director: 'J. J. Abrams',
        release_date: '2015-12-18',
      }),
    ).toMatchObject({
      title: 'The Force Awakens — Star Wars film JSON | swapi.build',
      robots: 'index,follow',
      canonicalPath: '/resource/films/7',
    });
  });

  it('applies the record head with its own canonical', () => {
    document.head.innerHTML = '<title>old</title>';
    applySeoMetadata('/resource/films/1', origin, { entity: film });
    expect(document.title).toBe('A New Hope — Star Wars film JSON | swapi.build');
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${origin}/resource/films/1`,
    );
    expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe(
      'index,follow',
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
