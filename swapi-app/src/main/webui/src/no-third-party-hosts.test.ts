import { describe, it, expect } from 'vitest';
import indexHtml from '../index.html?raw';

/**
 * The privacy page says the site serves its own fonts and loads nothing from a
 * font service. Until #52 every page fetched a stylesheet from
 * fonts.googleapis.com and font files from fonts.gstatic.com, which told Google
 * the visitor's IP address and User-Agent, and no-tracking.test.ts (scripts
 * only) read that as clean.
 *
 * So: nothing the document shell links, and nothing a stylesheet imports or
 * fetches, may live on another host. The one third-party load of the site is
 * the Cloudflare Web Analytics beacon, which the server injects and the privacy
 * page names (see no-tracking.test.ts).
 */
const REMOTE = /^\s*(https?:)?\/\//i;

const FONT_SERVICES =
  /fonts\.googleapis|fonts\.gstatic|fonts\.bunny\.net|use\.typekit|typekit\.net|cdnfonts|fontshare|fonts\.net|rsms\.me/i;

/**
 * Globbed rather than listed by name: a guard that only knows today's files
 * stops guarding the day someone adds a stylesheet.
 */
const stylesheets = import.meta.glob('./**/*.css', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const sources = import.meta.glob(['./**/*.ts', '!./**/*.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** Every address a stylesheet makes the browser fetch: `@import` and `url()`. */
function fetchedBy(css: string): string[] {
  const imports = [...css.matchAll(/@import\s*(?:url\(\s*)?['"]?([^'")\s;]+)/gi)].map((m) => m[1]);
  const urls = [...css.matchAll(/url\(\s*['"]?([^'")]+)/gi)].map((m) => m[1]);
  return [...imports, ...urls];
}

describe('no third-party stylesheet or font host', () => {
  it('index.html links nothing on another host', () => {
    const links = [...indexHtml.matchAll(/<link\b[^>]*>/gi)].map((m) => m[0]);
    // Without this, a shell with no <link> at all would pass vacuously.
    expect(links.length).toBeGreaterThan(0);
    const remote = links.filter((link) => {
      const href = link.match(/\bhref\s*=\s*["']?([^"'\s>]+)/i)?.[1] ?? '';
      return REMOTE.test(href);
    });
    expect(remote).toEqual([]);
  });

  it('index.html has no inline stylesheet (it could import one from another host)', () => {
    expect(indexHtml).not.toMatch(/<style\b/i);
  });

  it('finds the stylesheets it is meant to police', () => {
    const names = Object.keys(stylesheets);
    expect(names).toEqual(
      expect.arrayContaining([
        './style.css',
        './styles/tokens.css',
        './styles/base.css',
        './styles/components.css',
      ]),
    );
    for (const [name, css] of Object.entries(stylesheets)) {
      expect(css.length, `${name} resolved empty`).toBeGreaterThan(100);
    }
  });

  it('no stylesheet imports or fetches anything from another host', () => {
    // Without this, an extractor that finds nothing would pass vacuously: the
    // stylesheets import each other and fetch the font files.
    const all = Object.values(stylesheets).flatMap(fetchedBy);
    expect(all).toEqual(
      expect.arrayContaining(['./styles/tokens.css', '../fonts/inter-latin.woff2']),
    );
    const remote = Object.entries(stylesheets).flatMap(([name, css]) =>
      fetchedBy(css)
        .filter((address) => REMOTE.test(address))
        .map((address) => `${name}: ${address}`),
    );
    expect(remote).toEqual([]);
  });

  it('nothing names a font service host', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(5);
    const offenders = Object.entries({ ...sources, ...stylesheets, 'index.html': indexHtml })
      .filter(([, text]) => FONT_SERVICES.test(text))
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
