// Layout shift and <main> swaps on the first load (slice E, 2.11.0+).
//
// Run against a built server, outside CI (like verify-webmcp.cjs):
//   SWAPI_TEST_URL=http://127.0.0.1:8099 npm run measure:cls
//   SWAPI_TEST_URL=https://swapi.build npm run measure:cls -- --rounds 1
//
// Chromium from Playwright, two profiles (phone 360x640 at 2x, desktop 1280x800),
// CPU 4x slower and a slow 4G network through CDP: without the throttling a local
// machine paints everything before the first frame and the flash never shows.
//
// Per address and profile it prints the median CLS, the median number of <main>
// swaps (childList mutations of #main-content with removed nodes, counted from
// readyState "interactive" on, so the parser inserting the server's content never
// counts), the median LCP and the elements that shifted. On the phone profile,
// every server-rendered address must not scroll sideways (scrollWidth <= 360).
//
// More than one round is refused when the target is not localhost or 127.0.0.1:
// five rounds against production would put about 60 synthetic visits on /stats.
const { chromium } = require('playwright');

const base = (process.env.SWAPI_TEST_URL || 'http://127.0.0.1:8099').replace(/\/$/, '');
const roundsArg = process.argv.indexOf('--rounds');
const rounds = roundsArg > 0 ? Number(process.argv[roundsArg + 1]) : 5;
const host = new URL(base).hostname;
const local = host === 'localhost' || host === '127.0.0.1';

if (!Number.isInteger(rounds) || rounds < 1) {
  console.error(`--rounds must be a positive integer, got ${process.argv[roundsArg + 1]}`);
  process.exit(2);
}
if (!local && rounds > 1) {
  console.error(`refusing ${rounds} rounds against ${host}: use --rounds 1 outside localhost`);
  process.exit(2);
}

const PATHS = ['/', '/docs', '/docs/mcp', '/about', '/resource/films/1', '/resource/people/1'];
// The addresses the server draws in this version (seo/site.json, "ssr": true).
// The record addresses are drawn on the server from 2.12.0: against an older
// jar (the "before" of slice E2) their zero-swap check fails, as expected.
const SERVER_RENDERED = new Set(['/', '/docs', '/docs/mcp', '/about', '/resource/films/1', '/resource/people/1']);
const PROFILES = [
  { name: 'phone', viewport: { width: 360, height: 640 }, deviceScaleFactor: 2, isMobile: true },
  { name: 'desktop', viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1, isMobile: false },
];
// The home's live count line gives up after 3 s (LIVE_TIMEOUT_MS, pages/home.ts).
const SETTLE_MS = 3000;

function initScript() {
  const state = { cls: 0, shifts: [], swaps: 0, lcp: 0 };
  window.__swMeasure = state;
  const describe = (node) => {
    if (!node || node.nodeType !== 1) return node ? node.nodeName : '?';
    const id = node.id ? `#${node.id}` : '';
    const cls = node.classList && node.classList.length ? `.${[...node.classList].join('.')}` : '';
    return `${node.localName}${id}${cls}`;
  };
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      if (entry.hadRecentInput) continue;
      state.cls += entry.value;
      for (const source of entry.sources || []) state.shifts.push(describe(source.node));
    }
  }).observe({ type: 'layout-shift', buffered: true });
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) state.lcp = entry.startTime;
  }).observe({ type: 'largest-contentful-paint', buffered: true });
  const watch = () => {
    const main = document.getElementById('main-content');
    if (!main) return;
    new MutationObserver((records) => {
      for (const record of records) if (record.removedNodes.length > 0) state.swaps++;
    }).observe(main, { childList: true });
  };
  if (document.readyState === 'interactive' || document.readyState === 'complete') watch();
  else
    document.addEventListener('readystatechange', function onChange() {
      if (document.readyState !== 'interactive') return;
      document.removeEventListener('readystatechange', onChange);
      watch();
    });
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

async function measure(browser, profile, path) {
  const context = await browser.newContext({
    viewport: profile.viewport,
    deviceScaleFactor: profile.deviceScaleFactor,
    isMobile: profile.isMobile,
  });
  try {
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 150,
      downloadThroughput: (1.6 * 1024 * 1024) / 8,
      uploadThroughput: (750 * 1024) / 8,
    });
    await page.addInitScript(initScript);
    await page.goto(`${base}${path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(SETTLE_MS);
    return await page.evaluate(() => {
      const state = window.__swMeasure;
      let widest = null;
      for (const el of document.querySelectorAll('body *')) {
        const right = el.getBoundingClientRect().right;
        if (!widest || right > widest.right) widest = { right, el };
      }
      const name = widest
        ? `${widest.el.localName}${widest.el.className ? `.${String(widest.el.className).trim().split(/\s+/).join('.')}` : ''} (${Math.round(widest.right)}px)`
        : '';
      return {
        cls: state.cls,
        swaps: state.swaps,
        lcp: state.lcp,
        shifts: state.shifts,
        scrollWidth: document.documentElement.scrollWidth,
        widest: name,
      };
    });
  } finally {
    await context.close();
  }
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const failures = [];
  console.log(`target ${base}, ${rounds} round(s) per address and profile`);
  try {
    for (const profile of PROFILES) {
      for (const path of PATHS) {
        const results = [];
        for (let i = 0; i < rounds; i++) results.push(await measure(browser, profile, path));
        const cls = median(results.map((r) => r.cls));
        const swaps = median(results.map((r) => r.swaps));
        const lcp = median(results.map((r) => r.lcp));
        const shifted = [...new Set(results.flatMap((r) => r.shifts))];
        const width = Math.max(...results.map((r) => r.scrollWidth));
        console.log(
          `${profile.name.padEnd(7)} ${path.padEnd(20)} CLS ${cls.toFixed(4)}  main swaps ${swaps}  LCP ${Math.round(lcp)} ms` +
            (profile.name === 'phone' ? `  scrollWidth ${width}` : '') +
            (shifted.length ? `  shifted: ${shifted.join(', ')}` : ''),
        );
        if (!SERVER_RENDERED.has(path)) continue;
        if (swaps !== 0) failures.push(`${profile.name} ${path}: ${swaps} <main> swap(s) on the first load`);
        if (cls > 0.1) failures.push(`${profile.name} ${path}: CLS ${cls.toFixed(4)} > 0.1`);
        if (profile.name === 'phone' && width > 360) {
          const widest = results.find((r) => r.scrollWidth === width).widest;
          failures.push(`phone ${path}: scrollWidth ${width} > 360 (widest: ${widest})`);
        }
      }
    }
  } finally {
    await browser.close();
  }
  console.log();
  if (failures.length === 0) {
    console.log('PASS server-rendered addresses: zero <main> swaps, CLS <= 0.1, no sideways scroll at 360 px');
  } else {
    for (const failure of failures) console.log(`FAIL ${failure}`);
    process.exitCode = 1;
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
