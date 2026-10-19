import { describe, it, expect } from 'vitest';
import pkg from '../package.json?raw';
import main from './main.ts?raw';

/**
 * The privacy page promises "no analytics or tracking scripts of any kind", and
 * the self-hosted origin has no Vercel edge to serve /_vercel/* (Quinoa's SPA
 * fallback would answer those paths with index.html). Regression guard for the
 * Vercel Web Analytics / Speed Insights beacons that the SPA used to inject.
 */
const sources = {
  ...(import.meta.glob(['./**/*.ts', '!./**/*.test.ts'], {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
};

describe('no tracking', () => {
  it('declares no @vercel runtime packages', () => {
    const manifest = JSON.parse(pkg) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const all = { ...manifest.dependencies, ...manifest.devDependencies };
    expect(Object.keys(all).filter((name) => name.startsWith('@vercel/'))).toEqual([]);
  });

  it('main.ts injects no analytics or speed-insights script', () => {
    expect(main).not.toMatch(/@vercel\//);
    expect(main).not.toMatch(/injectSpeedInsights|\binject\(/);
  });

  it('no frontend source loads /_vercel/* scripts', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(5);
    const offenders = Object.entries(sources)
      .filter(([, code]) => /@vercel\/|\/_vercel\//.test(code))
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
