import { pill, sectionLabel, terminalMarkup, initTerminal, indexRows } from '../ui/components';
import { RESOURCES } from '../constants';
import { fetchStats } from '../api';

const TERM = {
  idPrefix: 'home',
  suggestions: ['people/1', 'planets/1', 'starships/9', 'films/1'],
};

/** After this the live line gives up and disappears; the home never waits on it. */
export const LIVE_TIMEOUT_MS = 3000;

/** Same as the `max-age` of GET /api/stats: a newer count would not exist yet. */
const LIVE_CACHE_MS = 60_000;

let liveCount: { total: number; at: number } | null = null;

/** Test hook: forget the cached count. */
export function resetLiveCount(): void {
  liveCount = null;
}

/** Formats a count with the site's locale (the `lang` of the document). */
export function formatRequestCount(total: number): string {
  const locale = document.documentElement.lang || 'en';
  return new Intl.NumberFormat(locale).format(total);
}

export function renderHome(container: HTMLElement): Promise<void> {
  container.innerHTML = `
    <section class="sw-hero">
      <div class="sw-starfield" aria-hidden="true"></div>
      <p class="sw-greeting">A long time ago in a galaxy far, far away…</p>
      <h1>The Star Wars API</h1>
      <p class="sw-sub">All the Star Wars data you've ever wanted. Free, open source, REST + MCP — built with Quarkus and GraalVM.</p>
      <div class="sw-cta-row">
        ${pill('▶ Get started', '/docs', 'solid')}
        ${pill('MCP for agents', '/docs/mcp', 'ghost')}
        ${pill('WebMCP in the browser', '/docs/webmcp', 'ghost')}
      </div>
      <p class="sw-live" hidden></p>
    </section>
    <div class="sw-term-wrap">${terminalMarkup(TERM)}</div>
    <div class="sw-band">
      ${sectionLabel('The resources')}
      ${indexRows(RESOURCES.map((r) => ({ title: r.title, endpoint: r.endpoint, href: `/resource/${r.key}` })))}
    </div>
  `;
  initTerminal(container, TERM);
  return showLiveCount(container.querySelector<HTMLElement>('.sw-live')!);
}

/**
 * Fills the "N requests in the last 24 h" line from GET /api/stats?period=24h.
 * The line starts hidden and is removed on any failure, timeout or unusable
 * answer, so the rest of the home never depends on it. A count is reused for
 * 60 s (the endpoint's own cache lifetime) across visits within the SPA.
 */
async function showLiveCount(line: HTMLElement): Promise<void> {
  let total: number;
  if (liveCount && Date.now() - liveCount.at < LIVE_CACHE_MS) {
    total = liveCount.total;
  } else {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), LIVE_TIMEOUT_MS);
    try {
      const { data } = await fetchStats<{ total?: unknown }>('24h', controller.signal);
      if (typeof data?.total !== 'number' || !Number.isFinite(data.total) || data.total < 1) {
        throw new Error('no usable total');
      }
      total = data.total;
      liveCount = { total, at: Date.now() };
    } catch {
      line.remove();
      return;
    } finally {
      clearTimeout(timer);
    }
  }
  const noun = total === 1 ? 'request' : 'requests';
  line.innerHTML = `<span class="sw-live-count">${formatRequestCount(total)}</span> ${noun} in the last 24 h · <a href="/stats">see stats</a>`;
  line.hidden = false;
}
