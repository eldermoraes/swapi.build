import { describe, expect, it } from 'vitest';
import registry from '../../java/com/eldermoraes/seo/SeoMetadataRegistry.java?raw';
import { getSeoMetadata, PUBLIC_SEO_ROUTES } from './seo';

/**
 * Provisional, until the metadata moves to a single JSON file (slice D): the
 * server (SeoMetadataRegistry.java) renders the head and the client (seo.ts)
 * rewrites it on every navigation, so both must say the same thing. This reads
 * the Java source and compares each route(...) with getSeoMetadata(path).
 * Fragile to formatting changes in the Java file on purpose: it fails loudly.
 */
const HOME_TITLE = 'SWAPI alternative: free Star Wars API and MCP server | swapi.build';

function javaConstant(name: string): string {
  const match = registry.match(new RegExp(`static final String ${name} = "([^"]*)";`));
  if (!match) throw new Error(`constant ${name} not found in SeoMetadataRegistry.java`);
  return match[1];
}

function javaRoutes(): { path: string; title: string; description: string }[] {
  const constants: Record<string, string> = { HOME_DESCRIPTION: javaConstant('HOME_DESCRIPTION') };
  const pattern = /route\("([^"]+)",\s*"([^"]*)",\s*(?:"([^"]*)"|([A-Z_]+))/g;
  return [...registry.matchAll(pattern)].map((m) => ({
    path: m[1],
    title: m[2],
    description: m[3] ?? constants[m[4]],
  }));
}

describe('SEO metadata parity between SeoMetadataRegistry.java and seo.ts', () => {
  const routes = javaRoutes();

  it('finds every public route in the Java registry', () => {
    expect(routes.map((r) => r.path).sort()).toEqual(PUBLIC_SEO_ROUTES.map((r) => r.path).sort());
  });

  it.each(routes.map((r) => [r.path, r] as const))(
    '%s has the same title and description',
    (path, java) => {
      const meta = getSeoMetadata(path);
      expect(meta.title).toBe(java.title);
      expect(meta.description).toBe(java.description);
    },
  );

  it('gives an unknown path the same fallback title as the server', () => {
    const fallback = registry.match(/new SeoMetadata\(path, "\/", "([^"]*)", HOME_DESCRIPTION/);
    expect(fallback?.[1]).toBe(HOME_TITLE);
    const meta = getSeoMetadata('/nao-existe');
    expect(meta.title).toBe(HOME_TITLE);
    expect(meta.description).toBe(javaConstant('HOME_DESCRIPTION'));
  });

  it('gives a resource detail the same title and description as the server (SeoRoutesTest literals)', () => {
    const meta = getSeoMetadata('/resource/people/1');
    expect(meta.title).toBe('People #1 | swapi.build');
    expect(meta.description).toBe('Star Wars people record #1 from the swapi.build REST API.');
  });
});
