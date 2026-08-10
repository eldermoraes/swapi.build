import { describe, it, expect } from 'vitest';
import tokens from '../styles/tokens.css?raw';
import base from '../styles/base.css?raw';
import components from '../styles/components.css?raw';
import pages from '../style.css?raw';

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

describe('design tokens', () => {
  it.each(REQUIRED)('defines %s', (token) => {
    expect(tokens).toContain(token);
  });

  it('has retired the legacy aliases now that every page consumes tokens', () => {
    expect(tokens).not.toContain('--accent:');
    expect(tokens).not.toContain('--json-key:');
    expect(tokens).not.toContain('--bg-primary:');
  });

  it.each([
    ['base.css', base],
    ['components.css', components],
    ['style.css', pages],
  ])('%s declares no raw hex — tokens.css is the only source of colour', (_name, css) => {
    const hex = css.replace(STARFIELD_WHITE, '').match(/#[0-9a-fA-F]{3,8}\b/g);
    expect(hex ?? []).toEqual([]);
  });
});
