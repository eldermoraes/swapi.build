import { describe, it, expect } from 'vitest';
import tokens from '../styles/tokens.css?raw';

const REQUIRED = [
  '--sw-ink: #000000',
  '--sw-gold: #ffe81f',
  '--sw-cyan: #5ce1ff',
  '--sw-term-bg: #030a12',
  '--sw-term-dim: #4e90af',
  '--sw-surface-raised: #0d0d0d',
  '--sw-font-display:',
  '--sw-radius-pill: 999px',
  '--sw-width-terminal: 860px',
];

/**
 * The starfield paints 12 stars as radial gradients whose only difference is the
 * alpha of pure white — depth, not a colour role. Tokenising twelve one-off
 * opacities would be noise, so it is the single sanctioned exception.
 */
const STARFIELD_WHITE = /#ffffff[0-9a-f]{2}/gi;

/**
 * Globbed rather than listed by name: a guard that only knows about today's
 * files stops guarding the moment someone adds a new stylesheet.
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

const consumerSheets = Object.entries(stylesheets)
  .map(([path, css]) => [path.split('/').pop()!, css] as const)
  .filter(([name]) => name !== 'tokens.css')
  .sort(([a], [b]) => a.localeCompare(b));

describe('design tokens', () => {
  it.each(REQUIRED)('defines %s', (token) => {
    expect(tokens).toContain(token);
  });

  it('has retired the legacy aliases now that every page consumes tokens', () => {
    expect(tokens).not.toContain('--accent:');
    expect(tokens).not.toContain('--json-key:');
    expect(tokens).not.toContain('--bg-primary:');
  });

  it('finds the stylesheets it is meant to police', () => {
    // Without this, a broken glob would let every check below pass vacuously.
    // A superset assertion: new stylesheets are welcome, silence is not.
    const names = consumerSheets.map(([name]) => name);
    expect(names).toEqual(expect.arrayContaining(['base.css', 'components.css', 'style.css']));
    for (const [name, css] of consumerSheets) {
      expect(css.length, `${name} resolved empty`).toBeGreaterThan(100);
    }
  });

  it.each(consumerSheets)(
    '%s declares no raw colour — tokens.css is the only source of colour',
    (_name, css) => {
      // Hex is not the only way to write a colour: an rgba() literal in a
      // consumer sheet is the same rule broken, and the hex-only guard read it
      // as clean. Both forms are checked against the same exception.
      const raw = css.replace(STARFIELD_WHITE, '').match(/#[0-9a-fA-F]{3,8}\b|(?:rgb|hsl)a?\(/g);
      expect(raw ?? []).toEqual([]);
    },
  );
});
