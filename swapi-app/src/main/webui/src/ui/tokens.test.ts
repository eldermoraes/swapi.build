import { describe, it, expect } from 'vitest';
import tokens from '../styles/tokens.css?raw';

const REQUIRED = [
  '--sw-ink: #000000',
  '--sw-gold: #ffe81f',
  '--sw-cyan: #5ce1ff',
  '--sw-term-bg: #030a12',
  '--sw-term-dim: #4e90af',
  '--sw-font-display:',
  '--sw-radius-pill: 999px',
  '--sw-width-terminal: 860px',
];

describe('design tokens', () => {
  it.each(REQUIRED)('defines %s', (token) => {
    expect(tokens).toContain(token);
  });
  it('has retired the legacy aliases now that every page consumes tokens', () => {
    expect(tokens).not.toContain('--accent:');
    expect(tokens).not.toContain('--json-key:');
    expect(tokens).not.toContain('--bg-primary:');
  });
});
