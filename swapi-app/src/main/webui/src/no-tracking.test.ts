import { describe, it, expect } from 'vitest';
import pkg from '../package.json?raw';
import indexHtml from '../index.html?raw';
import main from './main.ts?raw';

/**
 * The privacy page promises no cookies and no trackers. The one measurement
 * script allowed is the Cloudflare Web Analytics beacon, which sets no cookie
 * and uses no local storage. It is NOT part of the frontend: the server injects
 * it into the HTML only when CF_WEB_ANALYTICS_TOKEN is set (WebAnalytics.java,
 * guarded by WebAnalyticsTest and WebAnalyticsBeaconTest on the Java side).
 *
 * So the frontend itself must stay free of any tracking: no @vercel packages or
 * /_vercel/* beacons (the self-hosted origin has no Vercel edge), no known
 * tracker, no cookie, and no third-party script in index.html.
 */
const sources = {
  ...(import.meta.glob(['./**/*.ts', '!./**/*.test.ts'], {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
};

const TRACKERS =
  /@vercel\/|\/_vercel\/|googletagmanager|google-analytics|gtag\(|plausible|posthog|segment\.(com|io)|hotjar|clarity\.ms|connect\.facebook|fbq\(|mixpanel|amplitude|umami|matomo|cloudflareinsights/i;

describe('no tracking', () => {
  it('declares no @vercel or analytics runtime packages', () => {
    const manifest = JSON.parse(pkg) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const all = { ...manifest.dependencies, ...manifest.devDependencies };
    expect(
      Object.keys(all).filter((name) => name.startsWith('@vercel/') || TRACKERS.test(name)),
    ).toEqual([]);
  });

  it('main.ts injects no analytics or speed-insights script', () => {
    expect(main).not.toMatch(/@vercel\//);
    expect(main).not.toMatch(/injectSpeedInsights|\binject\(/);
  });

  it('no frontend source loads a tracker or sets a cookie', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(5);
    const offenders = Object.entries(sources)
      .filter(([, code]) => TRACKERS.test(code) || /document\.cookie/.test(code))
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });

  it('index.html loads no third-party script (the beacon is injected by the server only)', () => {
    expect(indexHtml).not.toMatch(TRACKERS);
    const scriptSources = [...indexHtml.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)].map(
      (m) => m[1],
    );
    expect(scriptSources.length).toBeGreaterThan(0);
    expect(scriptSources.filter((src) => /^(https?:)?\/\//i.test(src))).toEqual([]);
  });

  // 2.11.0: one inline script, the one that puts the `js` class on <html> before
  // the first paint (the tabs of /docs/mcp hide their inactive panels under it).
  it('the only inline script of index.html is the one that sets the js class', () => {
    const inline = [
      ...indexHtml.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi),
    ].map((m) => m[1].trim());
    expect(inline).toEqual(["document.documentElement.classList.add('js')"]);
  });
});
