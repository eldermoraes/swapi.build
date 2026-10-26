import { describe, it, expect } from 'vitest';
import { isServerPath } from './spa-links';

describe('links the SPA router must not swallow', () => {
  it.each([
    '/api/stats',
    '/api/stats?period=30d',
    '/api',
    '/api/people/1',
    '/mcp',
    '/openapi.json',
    '/robots.txt',
    '/llms.txt',
    '/sitemap.xml',
  ])(
    '%s goes to the server (regression: /api/stats on /privacy rendered the home page)',
    (href) => {
      expect(isServerPath(href)).toBe(true);
    },
  );

  it.each([
    '/',
    '/stats',
    '/stats?period=30d',
    '/docs',
    '/docs/mcp',
    '/privacy',
    '/resource/people',
    '/apix',
  ])('%s stays in the SPA', (href) => {
    expect(isServerPath(href)).toBe(false);
  });
});
