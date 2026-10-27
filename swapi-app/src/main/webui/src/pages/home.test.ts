import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import homeMain from '../../../../test/resources/ssr/home.html?raw';

const fetchStats = vi.fn();
const fetchEndpoint = vi.fn();
vi.mock('../api', () => ({
  fetchEndpoint: (...args: unknown[]) => fetchEndpoint(...args),
  fetchStats: (...args: unknown[]) => fetchStats(...args),
  ApiError: class ApiError extends Error {},
}));

import { bindHome, formatRequestCount, resetLiveCount, LIVE_TIMEOUT_MS } from './home';

function stats(total: number) {
  return Promise.resolve({ data: { period: '24h', total }, status: 200 });
}

/** The home as the server drew it (fixture from SsrFixturesTest). */
function serverHome(): HTMLElement {
  const holder = document.createElement('div');
  holder.innerHTML = homeMain;
  return holder.querySelector<HTMLElement>('main')!;
}

// 2.11.0: the server draws the home (templates/ssr/home.html); the client binds
// the terminal and fills the live line, and never redraws the page.
describe('home page (Holonet Terminal)', () => {
  beforeEach(() => {
    resetLiveCount();
    fetchStats.mockReset();
    fetchStats.mockImplementation(() => stats(0));
    fetchEndpoint.mockReset();
    fetchEndpoint.mockResolvedValue({ data: { name: 'Luke Skywalker' }, status: 200 });
  });

  it('binds the server markup without replacing it', () => {
    const container = serverHome();
    const before = Array.from(container.children);
    void bindHome(container);
    expect(Array.from(container.children)).toEqual(before);
    expect(container.querySelector('.sw-hero h1')!.textContent).toBe('The Star Wars API');
    expect(container.querySelectorAll('.sw-band .sw-index .sw-row')).toHaveLength(6);
  });

  it('binds the terminal chips', async () => {
    const container = serverHome();
    void bindHome(container);
    container.querySelector<HTMLButtonElement>('.sw-term-chips button')!.click();
    await vi.waitFor(() => expect(fetchEndpoint).toHaveBeenCalledWith('people/1'));
  });
});

describe('home page live request count', () => {
  beforeEach(() => {
    resetLiveCount();
    fetchStats.mockReset();
    document.documentElement.lang = 'en';
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the 24 h total from /api/stats with a link to /stats', async () => {
    fetchStats.mockImplementation(() => stats(1234));
    const container = serverHome();
    await bindHome(container);
    expect(fetchStats).toHaveBeenCalledTimes(1);
    expect(fetchStats.mock.calls[0][0]).toBe('24h');
    // Its own signal: a terminal query on the page must not cancel it.
    expect(fetchStats.mock.calls[0][1]).toBeInstanceOf(AbortSignal);
    const line = container.querySelector<HTMLElement>('.sw-live')!;
    expect(line.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      '1,234 requests in the last 24 h · see stats',
    );
    expect(line.querySelector('a')!.getAttribute('href')).toBe('/stats');
  });

  // The line keeps its place in the flow (CSS reserves one line and hides it
  // while empty), so filling it moves nothing below it (slice E1, CLS).
  it('stays empty and in place while the request is in flight', () => {
    fetchStats.mockImplementation(() => new Promise(() => {}));
    const container = serverHome();
    void bindHome(container);
    const line = container.querySelector<HTMLElement>('.sw-live')!;
    expect(container.contains(line)).toBe(true);
    expect(line.childNodes).toHaveLength(0);
  });

  it('leaves the line empty and in place when the request fails, the rest intact', async () => {
    fetchStats.mockImplementation(() => Promise.reject(new Error('HTTP 500')));
    const container = serverHome();
    await bindHome(container);
    const line = container.querySelector<HTMLElement>('.sw-live');
    expect(line).not.toBeNull();
    expect(line!.childNodes).toHaveLength(0);
    expect(container.querySelector('.sw-hero h1')!.textContent).toBe('The Star Wars API');
    expect(container.querySelectorAll('.sw-index .sw-row')).toHaveLength(6);
  });

  it('leaves the line empty and aborts the request when it takes too long', async () => {
    vi.useFakeTimers();
    let signal: AbortSignal | undefined;
    fetchStats.mockImplementation(
      (_period: string, s: AbortSignal) =>
        new Promise((_resolve, reject) => {
          signal = s;
          s.addEventListener('abort', () => reject(s.reason));
        }),
    );
    const container = serverHome();
    const done = bindHome(container);
    await vi.advanceTimersByTimeAsync(LIVE_TIMEOUT_MS);
    await done;
    expect(signal!.aborted).toBe(true);
    expect(container.querySelector('.sw-live')!.childNodes).toHaveLength(0);
  });

  it('leaves the line empty when the response has no usable total', async () => {
    fetchStats.mockImplementation(() => Promise.resolve({ data: { period: '24h' }, status: 200 }));
    const container = serverHome();
    await bindHome(container);
    expect(container.querySelector('.sw-live')!.childNodes).toHaveLength(0);
  });

  it('reuses the last count for 60 s instead of asking again on every visit', async () => {
    vi.useFakeTimers();
    fetchStats.mockImplementation(() => stats(42));
    await bindHome(serverHome());
    const again = serverHome();
    await bindHome(again);
    expect(fetchStats).toHaveBeenCalledTimes(1);
    expect(again.querySelector('.sw-live')!.textContent).toContain('42 requests');

    vi.advanceTimersByTime(60_000);
    await bindHome(serverHome());
    expect(fetchStats).toHaveBeenCalledTimes(2);
  });

  it('does not cache a failure: the next visit tries again', async () => {
    fetchStats.mockImplementationOnce(() => Promise.reject(new Error('down')));
    fetchStats.mockImplementationOnce(() => stats(7));
    await bindHome(serverHome());
    const again = serverHome();
    await bindHome(again);
    expect(fetchStats).toHaveBeenCalledTimes(2);
    expect(again.querySelector('.sw-live')!.textContent).toContain('7 requests');
  });

  it('uses the singular for a single request', async () => {
    fetchStats.mockImplementation(() => stats(1));
    const container = serverHome();
    await bindHome(container);
    expect(container.querySelector('.sw-live')!.textContent).toContain(
      '1 request in the last 24 h',
    );
  });
});

describe('formatRequestCount', () => {
  afterEach(() => {
    document.documentElement.lang = 'en';
  });

  it('formats with the site locale (the lang of the document)', () => {
    document.documentElement.lang = 'en';
    expect(formatRequestCount(1234567)).toBe('1,234,567');
    document.documentElement.lang = 'de';
    expect(formatRequestCount(1234567)).toBe('1.234.567');
  });

  it('falls back to English when the document has no lang', () => {
    document.documentElement.lang = '';
    expect(formatRequestCount(1234)).toBe('1,234');
  });
});
