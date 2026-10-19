import { describe, it, expect } from 'vitest';
import { renderPrivacy } from './privacy';

/** Rendered text with whitespace collapsed, so assertions ignore line wrapping. */
function text(): string {
  const container = document.createElement('main');
  renderPrivacy(container);
  return container.textContent!.replace(/\s+/g, ' ');
}

describe('privacy page', () => {
  it('names the self-hosted Oracle Cloud server as the host', () => {
    expect(text()).toContain('on Oracle Cloud, in the São Paulo region');
  });

  it('states that the web server keeps no access log and how long system logs live', () => {
    expect(text()).toContain('The web server keeps no access log');
    expect(text()).toContain('for up to 30 days');
  });

  it('no longer presents Vercel as the host (regression: the policy kept naming Vercel)', () => {
    const page = text();
    expect(page).not.toContain('is hosted on Vercel');
    expect(page).not.toContain('Hosting by Vercel');
    // Vercel may only appear as the previous host, while the move is under way.
    const mentions = [...page.matchAll(/[^.]*Vercel[^.]*\./g)].map((m) => m[0]);
    for (const sentence of mentions) {
      expect(sentence).toContain('the previous host');
    }
  });

  it('keeps Cloudflare as DNS only', () => {
    expect(text()).toContain('DNS by Cloudflare (DNS-only, no proxying');
  });

  it('carries the date of this revision', () => {
    expect(text()).toContain('Last updated: October 19, 2026');
  });
});
