import { initTerminal } from '../ui/components';
import { fetchStats } from '../api';

/** After this the live line gives up and stays empty; the home never waits on it. */
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

/**
 * The home comes drawn by the server (2.11.0, templates/ssr/home.html); the
 * client binds the terminal and fills the live line.
 */
export function bindHome(container: HTMLElement): Promise<void> {
  initTerminal(container, 'home');
  const line = container.querySelector<HTMLElement>('.sw-live');
  return line ? showLiveCount(line) : Promise.resolve();
}

/**
 * Fills the "N requests in the last 24 h" line from GET /api/stats?period=24h.
 * The line arrives empty, with one line of height reserved and hidden by the CSS
 * while empty, so filling it moves nothing (slice E1, CLS); on any failure,
 * timeout or unusable answer it simply stays empty. A count is reused for 60 s
 * (the endpoint's own cache lifetime) across visits within the SPA.
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
      return;
    } finally {
      clearTimeout(timer);
    }
  }
  const noun = total === 1 ? 'request' : 'requests';
  line.innerHTML = `<span class="sw-live-count">${formatRequestCount(total)}</span> ${noun} in the last 24 h · <a href="/stats">see stats</a>`;
}
