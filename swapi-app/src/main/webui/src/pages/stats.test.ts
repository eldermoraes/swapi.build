import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { StatsView } from './stats';

const fetchStats = vi.fn();
vi.mock('../api', () => ({
  fetchStats: (...args: unknown[]) => fetchStats(...args),
  ApiError: class ApiError extends Error {},
}));

import {
  renderStats,
  renderStatsData,
  parseFilters,
  filtersQuery,
  formatChange,
  percentChange,
  rankRows,
  bucketLabel,
} from './stats';

function view(period: '24h' | '7d' | '30d'): StatsView {
  const hourly = period === '24h';
  const n = hourly ? 24 : period === '7d' ? 7 : 30;
  const series = Array.from({ length: n }, (_, i) => ({
    start: hourly
      ? `2026-10-19T${String(i % 24).padStart(2, '0')}:00:00Z`
      : `2026-10-${String((i % 28) + 1).padStart(2, '0')}`,
    total: i === n - 1 ? 10 : 0,
    groups: i === n - 1 ? { rest: 6, mcp: 3, pages: 1 } : {},
  }));
  return {
    period,
    granularity: hourly ? 'hour' : 'day',
    from: series[0].start,
    to: series[n - 1].start,
    days: hourly ? 1 : n,
    total: 10,
    routes: { '/api/people/{id}': 6, '/mcp': 3, '/docs': 1 },
    groups: { rest: 6, mcp: 3, pages: 1, seo: 0, other: 0 },
    routeGroups: { '/api/people/{id}': 'rest', '/mcp': 'mcp', '/docs': 'pages' },
    series,
    previous: {
      from: 'a',
      to: 'b',
      total: 5,
      routes: { '/api/people/{id}': 5 },
      groups: { rest: 5, mcp: 0, pages: 0, seo: 0, other: 0 },
    },
  };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('stats page helpers', () => {
  it('reads valid filters from the query string and falls back on anything else', () => {
    expect(parseFilters('?period=30d&view=groups')).toEqual({ period: '30d', view: 'groups' });
    expect(parseFilters('')).toEqual({ period: '7d', view: 'routes' });
    expect(parseFilters('?period=1y&view=<script>')).toEqual({ period: '7d', view: 'routes' });
  });

  it('leaves the defaults out of the URL', () => {
    expect(filtersQuery({ period: '7d', view: 'routes' })).toBe('');
    expect(filtersQuery({ period: '24h', view: 'groups' })).toBe('?period=24h&view=groups');
  });

  it('computes the change against the previous window', () => {
    expect(percentChange(12, 10)).toBeCloseTo(20);
    expect(percentChange(5, 0)).toBeNull();
    expect(formatChange(12, 10)).toBe('▲ 20%');
    expect(formatChange(5, 10)).toBe('▼ 50%');
    expect(formatChange(10, 10)).toBe('±0%');
    expect(formatChange(3, 0)).toBe('new');
    expect(formatChange(0, 0)).toBe('—');
  });

  it('labels buckets in UTC', () => {
    expect(bucketLabel('2026-10-19T13:00:00Z', 'hour')).toBe('13:00');
    expect(bucketLabel('2026-10-09', 'day')).toBe('Oct 9');
  });

  it('ranks routes by count, and groups with all five present', () => {
    expect(rankRows(view('7d'), 'routes').map((r) => r.key)).toEqual([
      '/api/people/{id}',
      '/mcp',
      '/docs',
    ]);
    const groups = rankRows(view('7d'), 'groups');
    expect(groups.map((r) => r.key)).toEqual(['rest', 'mcp', 'pages', 'seo', 'other']);
    expect(groups[0]).toMatchObject({ label: 'REST API', count: 6, previous: 5, share: 0.6 });
  });

  it('never shows a start date', () => {
    const html = renderStatsData(
      { ...view('7d'), countingSince: '2026-10-19T12:00:00Z' } as StatsView,
      'routes',
    );
    expect(html).not.toContain('countingSince');
    expect(html).not.toMatch(/since/i);
    expect(html).not.toContain('2026-10-19T12:00:00Z');
  });

  it('escapes route names', () => {
    const v = view('7d');
    v.routes = { '<img src=x>': 1 };
    v.routeGroups = { '<img src=x>': 'other' };
    expect(renderStatsData(v, 'routes')).not.toContain('<img src=x>');
  });
});

describe('stats page', () => {
  beforeEach(() => {
    fetchStats.mockReset();
    fetchStats.mockImplementation((period: '24h' | '7d' | '30d') =>
      Promise.resolve({ data: view(period), status: 200 }),
    );
    history.replaceState(null, '', '/stats');
  });

  it('loads 7 days by route by default, with totals, trend and ranking', async () => {
    const container = document.createElement('main');
    await renderStats(container);

    expect(fetchStats).toHaveBeenCalledWith('7d');
    expect(container.querySelector('h1')!.textContent).toBe('Usage stats');
    expect(container.querySelector('[data-period="7d"]')!.getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(container.querySelector('[data-view="routes"]')!.getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(container.querySelector('.stats-kpi-value')!.textContent).toBe('10');
    expect(container.querySelector('.stats-kpi-note')!.textContent).toBe(
      '▲ 100% vs the previous 7 days',
    );
    expect(container.querySelectorAll('.stats-bar')).toHaveLength(7);
    expect(container.querySelectorAll('.stats-ranking tbody tr')).toHaveLength(3);
    expect(container.querySelector('#stats-trend')!.textContent).toBe('Requests per day');
  });

  it('switches the period, fetches it and keeps it in the URL', async () => {
    const container = document.createElement('main');
    await renderStats(container);

    (container.querySelector('[data-period="24h"]') as HTMLButtonElement).click();
    await flush();

    expect(fetchStats).toHaveBeenLastCalledWith('24h');
    expect(container.querySelector('[data-period="24h"]')!.getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(container.querySelector('[data-period="7d"]')!.getAttribute('aria-pressed')).toBe(
      'false',
    );
    expect(container.querySelectorAll('.stats-bar')).toHaveLength(24);
    expect(container.querySelector('#stats-trend')!.textContent).toBe('Requests per hour');
    expect(window.location.search).toBe('?period=24h');

    (container.querySelector('[data-period="30d"]') as HTMLButtonElement).click();
    await flush();
    expect(container.querySelectorAll('.stats-bar')).toHaveLength(30);
    expect(window.location.search).toBe('?period=30d');
  });

  it('groups without fetching again, with a legend and stacked bars', async () => {
    const container = document.createElement('main');
    await renderStats(container);
    const calls = fetchStats.mock.calls.length;

    (container.querySelector('[data-view="groups"]') as HTMLButtonElement).click();
    await flush();

    expect(fetchStats.mock.calls.length).toBe(calls);
    expect(container.querySelector('[data-view="groups"]')!.getAttribute('aria-pressed')).toBe(
      'true',
    );
    const rows = [...container.querySelectorAll('.stats-ranking tbody tr')].map((r) =>
      r.getAttribute('data-row'),
    );
    expect(rows).toEqual(['rest', 'mcp', 'pages', 'seo', 'other']);
    expect(container.querySelectorAll('.stats-legend li')).toHaveLength(5);
    expect(container.querySelectorAll('.stats-bar:last-child .stats-seg')).toHaveLength(3);
    expect(window.location.search).toBe('?view=groups');
  });

  it('reuses a window already loaded', async () => {
    const container = document.createElement('main');
    await renderStats(container);
    (container.querySelector('[data-period="30d"]') as HTMLButtonElement).click();
    await flush();
    (container.querySelector('[data-period="7d"]') as HTMLButtonElement).click();
    await flush();

    expect(fetchStats.mock.calls.map((c) => c[0])).toEqual(['7d', '30d']);
  });

  it('starts from the filters in the URL', async () => {
    history.replaceState(null, '', '/stats?period=30d&view=groups');
    const container = document.createElement('main');
    await renderStats(container);

    expect(fetchStats).toHaveBeenCalledWith('30d');
    expect(container.querySelector('[data-view="groups"]')!.getAttribute('aria-pressed')).toBe(
      'true',
    );
  });

  it('shows an error with a retry when the API fails', async () => {
    fetchStats.mockRejectedValueOnce(new Error('boom'));
    const container = document.createElement('main');
    await renderStats(container);

    expect(container.querySelector('[role="alert"]')).toBeTruthy();
    (container.querySelector('[data-retry]') as HTMLButtonElement).click();
    await flush();
    expect(container.querySelector('[role="alert"]')).toBeNull();
    expect(container.querySelectorAll('.stats-bar')).toHaveLength(7);
  });

  it('drops a response that arrives after the user left the page', async () => {
    const container = document.createElement('main');
    await renderStats(container, () => false);

    expect(container.querySelector('.stats-bar')).toBeNull();
  });

  it('still renders a filter change after main.ts bumped its navigation generation', async () => {
    // Regression: main.ts counts every button click inside #main-content as a
    // "human action" and bumps its generation, so its isCurrent() turns false
    // on the very click that changes the filter. The response was dropped and
    // the spinner stayed forever (seen in the browser before release).
    let current = true;
    const container = document.createElement('main');
    await renderStats(container, () => current);
    current = false;

    (container.querySelector('[data-period="24h"]') as HTMLButtonElement).click();
    await flush();

    expect(container.querySelector('.spinner')).toBeNull();
    expect(container.querySelectorAll('.stats-bar')).toHaveLength(24);
  });

  it('drops a response once another page replaced it', async () => {
    let resolve!: (v: unknown) => void;
    const container = document.createElement('main');
    await renderStats(container);
    fetchStats.mockImplementationOnce(() => new Promise((r) => (resolve = r)));

    (container.querySelector('[data-period="30d"]') as HTMLButtonElement).click();
    container.innerHTML = '<h1>Another page</h1>';
    resolve({ data: view('30d'), status: 200 });
    await flush();

    expect(container.innerHTML).toBe('<h1>Another page</h1>');
  });
});
