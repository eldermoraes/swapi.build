import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const fetchStats = vi.fn();
vi.mock('../api', () => ({
  fetchEndpoint: vi.fn().mockResolvedValue({ data: {}, status: 200 }),
  fetchStats: (...args: unknown[]) => fetchStats(...args),
  ApiError: class ApiError extends Error {},
}));

import { renderHome, formatRequestCount, resetLiveCount, LIVE_TIMEOUT_MS } from './home';

function stats(total: number) {
  return Promise.resolve({ data: { period: '24h', total }, status: 200 });
}

describe('home page (Holonet Terminal)', () => {
  beforeEach(() => {
    resetLiveCount();
    fetchStats.mockReset();
    fetchStats.mockImplementation(() => stats(0));
  });

  it('renders greeting, display title, terminal and six resource rows', () => {
    const container = document.createElement('main');
    void renderHome(container);
    expect(container.querySelector('.sw-greeting')!.textContent).toContain('A long time ago');
    expect(container.querySelector('.sw-hero h1')!.textContent).toBe('The Star Wars API');
    expect(container.querySelector('.sw-term')).toBeTruthy();
    expect(container.querySelectorAll('.sw-index .sw-row')).toHaveLength(6);
    expect(container.querySelector('.resource-grid')).toBeNull(); // old cards are gone
  });

  it('constrains the resources band instead of letting it run full-bleed', () => {
    const container = document.createElement('main');
    void renderHome(container);
    const band = container.querySelector('.sw-band')!;
    expect(band).toBeTruthy();
    // The label and the rows travel together, so they stay aligned with the terminal above.
    expect(band.querySelector('.sw-section-label')).toBeTruthy();
    expect(band.querySelector('.sw-index')).toBeTruthy();
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
    const container = document.createElement('main');
    await renderHome(container);
    expect(fetchStats).toHaveBeenCalledTimes(1);
    expect(fetchStats.mock.calls[0][0]).toBe('24h');
    // Its own signal: a terminal query on the page must not cancel it.
    expect(fetchStats.mock.calls[0][1]).toBeInstanceOf(AbortSignal);
    const line = container.querySelector<HTMLElement>('.sw-live')!;
    expect(line.hidden).toBe(false);
    expect(line.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      '1,234 requests in the last 24 h · see stats',
    );
    expect(line.querySelector('a')!.getAttribute('href')).toBe('/stats');
  });

  it('stays hidden while the request is in flight', () => {
    fetchStats.mockImplementation(() => new Promise(() => {}));
    const container = document.createElement('main');
    void renderHome(container);
    expect(container.querySelector<HTMLElement>('.sw-live')!.hidden).toBe(true);
  });

  it('removes the line when the request fails, leaving the rest of the home intact', async () => {
    fetchStats.mockImplementation(() => Promise.reject(new Error('HTTP 500')));
    const container = document.createElement('main');
    await renderHome(container);
    expect(container.querySelector('.sw-live')).toBeNull();
    expect(container.querySelector('.sw-hero h1')!.textContent).toBe('The Star Wars API');
    expect(container.querySelectorAll('.sw-index .sw-row')).toHaveLength(6);
  });

  it('removes the line and aborts the request when it takes too long', async () => {
    vi.useFakeTimers();
    let signal: AbortSignal | undefined;
    fetchStats.mockImplementation(
      (_period: string, s: AbortSignal) =>
        new Promise((_resolve, reject) => {
          signal = s;
          s.addEventListener('abort', () => reject(s.reason));
        }),
    );
    const container = document.createElement('main');
    const done = renderHome(container);
    expect(container.querySelector('.sw-live')).not.toBeNull();
    await vi.advanceTimersByTimeAsync(LIVE_TIMEOUT_MS);
    await done;
    expect(signal!.aborted).toBe(true);
    expect(container.querySelector('.sw-live')).toBeNull();
  });

  it('removes the line when the response has no usable total', async () => {
    fetchStats.mockImplementation(() => Promise.resolve({ data: { period: '24h' }, status: 200 }));
    const container = document.createElement('main');
    await renderHome(container);
    expect(container.querySelector('.sw-live')).toBeNull();
  });

  it('reuses the last count for 60 s instead of asking again on every visit', async () => {
    vi.useFakeTimers();
    fetchStats.mockImplementation(() => stats(42));
    await renderHome(document.createElement('main'));
    const again = document.createElement('main');
    await renderHome(again);
    expect(fetchStats).toHaveBeenCalledTimes(1);
    expect(again.querySelector('.sw-live')!.textContent).toContain('42 requests');

    vi.advanceTimersByTime(60_000);
    await renderHome(document.createElement('main'));
    expect(fetchStats).toHaveBeenCalledTimes(2);
  });

  it('does not cache a failure: the next visit tries again', async () => {
    fetchStats.mockImplementationOnce(() => Promise.reject(new Error('down')));
    fetchStats.mockImplementationOnce(() => stats(7));
    await renderHome(document.createElement('main'));
    const again = document.createElement('main');
    await renderHome(again);
    expect(fetchStats).toHaveBeenCalledTimes(2);
    expect(again.querySelector('.sw-live')!.textContent).toContain('7 requests');
  });

  it('uses the singular for a single request', async () => {
    fetchStats.mockImplementation(() => stats(1));
    const container = document.createElement('main');
    await renderHome(container);
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
