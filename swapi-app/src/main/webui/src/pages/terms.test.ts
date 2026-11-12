import { describe, it, expect } from 'vitest';
import { renderTerms } from './terms';

/** Rendered text with whitespace collapsed, so assertions ignore line wrapping. */
function text(): string {
  const container = document.createElement('main');
  renderTerms(container);
  return container.textContent!.replace(/\s+/g, ' ');
}

describe('terms page', () => {
  it('does not say the service scales to zero (regression: that described the previous host)', () => {
    // Production is a native executable that stays running under systemd, so
    // there is no cold start after a quiet period to warn about.
    expect(text()).not.toMatch(/scales? to zero/i);
    expect(text()).not.toMatch(/quiet period/i);
  });

  it('still gives no warranty and no uptime guarantee', () => {
    expect(text()).toContain('with no warranty of any kind and no uptime guarantee.');
  });

  it('carries the date of this revision', () => {
    expect(text()).toContain('Last updated: November 12, 2026');
  });
});
