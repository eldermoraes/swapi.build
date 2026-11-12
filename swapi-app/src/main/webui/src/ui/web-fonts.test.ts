import { describe, it, expect } from 'vitest';
import tokens from '../styles/tokens.css?raw';

/**
 * The web fonts are served by the site itself (#52): the woff2 files live in
 * src/fonts/, tokens.css declares one face per family, and Vite fingerprints
 * the files under /assets/ (immutable for a year, like the JS and the CSS).
 * They are the files Google Fonts served for the latin subset, so the
 * typography is the one the site had while it loaded them from there.
 */
const WEB_FONTS = [
  {
    family: 'Inter',
    file: 'inter-latin.woff2',
    // One variable file; the site loaded 400, 500, 600 and 700.
    weight: '400 700',
    licence: 'OFL-Inter.txt',
    authors: 'The Inter Project Authors',
  },
  {
    family: 'JetBrains Mono',
    file: 'jetbrains-mono-latin.woff2',
    // One variable file; the site loaded 400 and 500.
    weight: '400 500',
    licence: 'OFL-JetBrainsMono.txt',
    authors: 'The JetBrains Mono Project Authors',
  },
  {
    family: 'Pathway Gothic One',
    file: 'pathway-gothic-one-latin.woff2',
    weight: '400',
    licence: 'OFL-PathwayGothicOne.txt',
    authors: 'The Pathway Gothic Project Authors',
  },
];

const fontFiles = Object.keys(import.meta.glob('../fonts/*.woff2', { query: '?url' })).map((path) =>
  path.replace(/^.*\//, ''),
);

const licences = Object.fromEntries(
  Object.entries(
    import.meta.glob('../fonts/*.txt', {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>,
  ).map(([path, text]) => [path.replace(/^.*\//, ''), text]),
);

function faces(family: string): string[] {
  const all = tokens.match(/@font-face\s*{[^}]*}/g) ?? [];
  return all.filter((face) => face.includes(`font-family: '${family}';`));
}

describe('self-hosted web fonts', () => {
  for (const { family, file, weight, licence, authors } of WEB_FONTS) {
    it(`'${family}' is one face served from ${file}, and it swaps`, () => {
      const [face, ...others] = faces(family);
      expect(face, `@font-face for '${family}'`).toBeDefined();
      expect(others).toEqual([]);
      // A relative address: Vite fingerprints the file under /assets/.
      expect(face).toContain(`src: url('../fonts/${file}') format('woff2');`);
      expect(face).toContain('font-display: swap;');
      expect(face).toContain(`font-weight: ${weight};`);
      expect(face).toContain('font-style: normal;');
    });

    it(`${file} is in src/fonts with the licence of '${family}'`, () => {
      expect(fontFiles).toContain(file);
      expect(licences[licence], licence).toBeDefined();
      expect(licences[licence]).toContain(authors);
      expect(licences[licence]).toContain('SIL OPEN FONT LICENSE Version 1.1');
    });
  }

  it('ships no font file without a face', () => {
    expect([...fontFiles].sort()).toEqual(WEB_FONTS.map(({ file }) => file).sort());
  });
});
