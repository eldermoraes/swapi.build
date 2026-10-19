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

  it('does not mention Vercel at all (regression: the policy kept naming it after the move)', () => {
    // The move to the self-hosted server is finished and nothing of the project
    // runs on Vercel any more, so not even a "previous host" paragraph remains.
    expect(text()).not.toMatch(/vercel/i);
  });

  it('keeps Cloudflare as DNS only', () => {
    expect(text()).toContain('DNS by Cloudflare (DNS-only, no proxying');
  });

  it('says page visits are measured by Cloudflare Web Analytics, without cookies or personal data', () => {
    expect(text()).toContain('measured with Cloudflare Web Analytics');
    expect(text()).toContain('It sets no cookie, uses no local storage');
    expect(text()).toContain('collects no personal data');
  });

  it('says the server counts requests per route and per day, without IP address', () => {
    expect(text()).toContain('The server also counts requests per route and per day (UTC)');
    expect(text()).toContain('no IP address');
    expect(text()).toContain('/api/stats');
  });

  it('no longer promises "no analytics" (regression: the policy must match the beacon)', () => {
    expect(text()).not.toMatch(/no analytics/i);
  });

  it('carries the date of this revision', () => {
    expect(text()).toContain('Last updated: October 19, 2026');
  });
});
