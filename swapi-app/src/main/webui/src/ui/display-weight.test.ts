import { describe, expect, it } from 'vitest';

/**
 * Issue #71: Pathway Gothic One ships a single 400 weight (DESIGN.md, section
 * 2). A heading takes the browser's bold unless its rule says otherwise, and
 * the browser then fakes a bold the face does not have. So every rule that sets
 * the display face sets the weight it has. Globbed like tokens.test.ts: a new
 * stylesheet is policed the day it lands.
 */
const stylesheets = {
  ...(import.meta.glob('../styles/*.css', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../style.css', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
};

/** The selector of every rule that sets the display face, with its declarations. */
function displayRules(css: string): { selector: string; body: string }[] {
  return Array.from(css.matchAll(/([^{}]+)\{([^{}]*)\}/g), ([, selector, body]) => ({
    selector: selector.replace(/\/\*[\s\S]*?\*\//g, '').trim(),
    body,
  })).filter(({ body }) => /font-family:\s*var\(--sw-font-display\)/.test(body));
}

describe('display face weight', () => {
  const rules = Object.values(stylesheets).flatMap(displayRules);

  it('finds the rules it is meant to police', () => {
    const selectors = rules.map(({ selector }) => selector);
    expect(selectors).toEqual(
      expect.arrayContaining(['.sw-page-title', '.sw-section-label', '.sw-hero h1', '.docs h2']),
    );
  });

  it('every rule that sets the display face sets font-weight: 400', () => {
    const missing = rules
      .filter(({ body }) => !/font-weight:\s*400\s*;/.test(body))
      .map(({ selector }) => selector);
    expect(missing).toEqual([]);
  });
});
