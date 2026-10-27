import { fetchStats, ApiError } from '../api';
import { escapeHtml } from '../utils';

/*
 * /stats — a small analytics page over GET /api/stats.
 *
 * Everything shown comes from the server's anonymous counters (route template,
 * count and server time per hour and per UTC day, nothing about any client).
 * The request chart is plain CSS bars and the latency chart one inline SVG;
 * no chart library. Filters live in the query string (?period=30d&view=groups)
 * so a view can be linked, and are validated before use.
 */

export type Period = '24h' | '7d' | '30d';
export type View = 'routes' | 'groups';
export type GroupId = 'rest' | 'mcp' | 'pages' | 'seo' | 'other';

export interface StatsBucket {
  start: string;
  total: number;
  groups: Partial<Record<GroupId, number>>;
}

/** Server times of a route or group in ms; only `count` (0) when it had no timed request. */
export interface Latency {
  count: number;
  mean?: number;
  min?: number;
  max?: number;
  stddev?: number;
  p50?: number;
  p95?: number;
  p99?: number;
}

export interface LatencyPoint {
  start: string;
  count: number;
  p50?: number;
  p95?: number;
}

/** `performance` of GET /api/stats (2.8.0+). */
export interface Performance {
  unit: 'ms';
  routes: Record<string, Latency>;
  groups: Record<GroupId, Latency>;
  series: LatencyPoint[];
}

export interface StatsView {
  period: Period;
  granularity: 'hour' | 'day';
  from: string;
  to: string;
  days: number;
  total: number;
  routes: Record<string, number>;
  groups: Record<GroupId, number>;
  routeGroups: Record<string, GroupId>;
  series: StatsBucket[];
  previous: {
    from: string;
    to: string;
    total: number;
    routes: Record<string, number>;
    groups: Record<GroupId, number>;
  };
  performance?: Performance;
}

export const PERIODS: { id: Period; label: string; previous: string }[] = [
  { id: '24h', label: '24 hours', previous: 'the previous 24 hours' },
  { id: '7d', label: '7 days', previous: 'the previous 7 days' },
  { id: '30d', label: '30 days', previous: 'the previous 30 days' },
];

export const VIEWS: { id: View; label: string }[] = [
  { id: 'routes', label: 'By route' },
  { id: 'groups', label: 'By group' },
];

/** Ids match com.eldermoraes.stats.RouteGroup (RouteGroupTest pins them). */
export const GROUPS: { id: GroupId; label: string; hint: string }[] = [
  { id: 'rest', label: 'REST API', hint: '/api and /openapi.json' },
  // 2.12.1: a subscriptions/listen is answered at once (McpTransportFilter),
  // so the group's latency no longer carries calls of minutes.
  {
    id: 'mcp',
    label: 'MCP',
    hint: '/mcp: every JSON-RPC call, including the lists a client reads when it connects',
  },
  // 2.11.0: an in-site navigation to a server-rendered page fetches its document.
  {
    id: 'pages',
    label: 'Pages and docs',
    hint: 'this site: full loads and in-site navigations since 2.11.0',
  },
  { id: 'seo', label: 'SEO', hint: 'robots.txt, sitemap.xml and llms.txt' },
  { id: 'other', label: 'Other', hint: 'unknown paths' },
];

const DEFAULTS = { period: '7d' as Period, view: 'routes' as View };
const numbers = new Intl.NumberFormat('en-US');
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function parseFilters(search: string): { period: Period; view: View } {
  const params = new URLSearchParams(search);
  const period = PERIODS.find((p) => p.id === params.get('period'))?.id ?? DEFAULTS.period;
  const view = VIEWS.find((v) => v.id === params.get('view'))?.id ?? DEFAULTS.view;
  return { period, view };
}

/** Query string for the filters; the defaults are left out so /stats stays /stats. */
export function filtersQuery(filters: { period: Period; view: View }): string {
  const params = new URLSearchParams();
  if (filters.period !== DEFAULTS.period) params.set('period', filters.period);
  if (filters.view !== DEFAULTS.view) params.set('view', filters.view);
  const query = params.toString();
  return query ? `?${query}` : '';
}

export function percentChange(current: number, previous: number): number | null {
  if (previous === 0) return null;
  return ((current - previous) / previous) * 100;
}

/** "▲ 12%", "▼ 3%", "±0%"; "new" when the previous window had nothing, "—" when neither had. */
export function formatChange(current: number, previous: number): string {
  const change = percentChange(current, previous);
  if (change === null) return current === 0 ? '—' : 'new';
  const rounded = Math.round(change);
  if (rounded === 0) return '±0%';
  return `${rounded > 0 ? '▲' : '▼'} ${Math.abs(rounded)}%`;
}

export function groupLabel(id: GroupId): string {
  return GROUPS.find((g) => g.id === id)?.label ?? 'Other';
}

/** "13:00" for an hour bucket, "Oct 19" for a day bucket (both UTC). */
export function bucketLabel(start: string, granularity: 'hour' | 'day'): string {
  if (granularity === 'hour') {
    return `${start.slice(11, 13)}:00`;
  }
  const [, month, day] = start.split('-');
  return `${MONTHS[Number(month) - 1] ?? month} ${Number(day)}`;
}

export interface RankRow {
  key: string;
  label: string;
  group: GroupId;
  count: number;
  previous: number;
  share: number;
}

/** Routes that had requests, or all five groups, by count. */
export function rankRows(view: StatsView, mode: View): RankRow[] {
  const share = (n: number) => (view.total > 0 ? n / view.total : 0);
  if (mode === 'groups') {
    return GROUPS.map((g) => ({
      key: g.id,
      label: g.label,
      group: g.id,
      count: view.groups[g.id] ?? 0,
      previous: view.previous.groups[g.id] ?? 0,
      share: share(view.groups[g.id] ?? 0),
    })).sort((a, b) => b.count - a.count);
  }
  return Object.entries(view.routes)
    .filter(([, count]) => count > 0)
    .map(([route, count]) => ({
      key: route,
      label: route,
      group: view.routeGroups[route] ?? 'other',
      count,
      previous: view.previous.routes[route] ?? 0,
      share: share(count),
    }))
    .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key));
}

function percent(share: number): string {
  const p = share * 100;
  if (p > 0 && p < 1) return '<1%';
  return `${Math.round(p)}%`;
}

function kpis(view: StatsView, mode: View): string {
  const period = PERIODS.find((p) => p.id === view.period)!;
  const unit = view.granularity === 'hour' ? 'hour' : 'day';
  const peak = view.series.reduce<StatsBucket | null>(
    (best, b) => (b.total > 0 && (!best || b.total > best.total) ? b : best),
    null,
  );
  const top = rankRows(view, mode)[0];
  const average = view.series.length ? view.total / view.series.length : 0;
  const tile = (label: string, value: string, note: string) => `
    <div class="stats-kpi sw-panel">
      <span class="stats-kpi-label">${escapeHtml(label)}</span>
      <span class="stats-kpi-value">${escapeHtml(value)}</span>
      <span class="stats-kpi-note">${escapeHtml(note)}</span>
    </div>`;
  return `
    <div class="stats-kpis">
      ${tile(
        'Requests',
        numbers.format(view.total),
        `${formatChange(view.total, view.previous.total)} vs ${period.previous}`,
      )}
      ${tile(
        mode === 'groups' ? 'Top group' : 'Top route',
        top && top.count > 0 ? top.label : '—',
        top && top.count > 0
          ? `${numbers.format(top.count)} requests, ${percent(top.share)}`
          : 'no requests yet',
      )}
      ${tile(
        `Busiest ${unit}`,
        peak ? bucketLabel(peak.start, view.granularity) : '—',
        peak ? `${numbers.format(peak.total)} requests` : 'no requests yet',
      )}
      ${tile(
        `Average per ${unit}`,
        numbers.format(Math.round(average * 10) / 10),
        `over ${view.series.length} ${unit}s`,
      )}
    </div>`;
}

function chart(view: StatsView, mode: View): string {
  const unit = view.granularity === 'hour' ? 'hour' : 'day';
  const max = Math.max(1, ...view.series.map((b) => b.total));
  const peak = Math.max(0, ...view.series.map((b) => b.total));
  const bars = view.series
    .map((b) => {
      const label = bucketLabel(b.start, view.granularity);
      const title = `${label}${view.granularity === 'hour' ? ' UTC' : ''} · ${numbers.format(b.total)} requests`;
      const segments =
        mode === 'groups'
          ? GROUPS.filter((g) => (b.groups[g.id] ?? 0) > 0)
              .map(
                (g) =>
                  `<span class="stats-seg stats-seg--${g.id}" style="height:${(((b.groups[g.id] ?? 0) / max) * 100).toFixed(2)}%"></span>`,
              )
              .join('')
          : b.total > 0
            ? `<span class="stats-seg stats-seg--total" style="height:${((b.total / max) * 100).toFixed(2)}%"></span>`
            : '';
      return `<div class="stats-bar" title="${escapeHtml(title)}">${segments}</div>`;
    })
    .join('');
  const first = view.series[0];
  const middle = view.series[Math.floor(view.series.length / 2)];
  const last = view.series[view.series.length - 1];
  const axis = [first, middle, last]
    .map((b) => `<span>${escapeHtml(b ? bucketLabel(b.start, view.granularity) : '')}</span>`)
    .join('');
  const legend =
    mode === 'groups'
      ? `<ul class="stats-legend">${GROUPS.map(
          (g) =>
            `<li><span class="stats-swatch stats-seg--${g.id}" aria-hidden="true"></span>${escapeHtml(g.label)}</li>`,
        ).join('')}</ul>`
      : '';
  const columns = mode === 'groups' ? GROUPS : [];
  const table = `
    <details class="stats-numbers">
      <summary>Show the numbers</summary>
      <div class="sw-table-wrap">
        <table class="sw-table stats-table">
          <thead><tr><th scope="col">${unit === 'hour' ? 'Hour (UTC)' : 'Day (UTC)'}</th><th scope="col">Requests</th>${columns
            .map((g) => `<th scope="col">${escapeHtml(g.label)}</th>`)
            .join('')}</tr></thead>
          <tbody>${view.series
            .map(
              (b) =>
                `<tr><td>${escapeHtml(bucketLabel(b.start, view.granularity))}</td><td>${numbers.format(b.total)}</td>${columns
                  .map((g) => `<td>${numbers.format(b.groups[g.id] ?? 0)}</td>`)
                  .join('')}</tr>`,
            )
            .join('')}</tbody>
        </table>
      </div>
    </details>`;
  return `
    <section class="stats-section" aria-labelledby="stats-trend">
      <h2 class="stats-heading" id="stats-trend">Requests per ${unit}</h2>
      ${legend}
      <div class="stats-bars" role="img"
        aria-label="${escapeHtml(`Requests per ${unit}: ${view.series.length} ${unit}s, peak ${numbers.format(peak)}`)}">${bars}</div>
      <div class="stats-axis" aria-hidden="true">${axis}</div>
      ${table}
    </section>`;
}

function changeCell(row: RankRow): string {
  return `<td class="stats-change">${escapeHtml(formatChange(row.count, row.previous))}</td>`;
}

function shareCell(row: RankRow): string {
  return `<td class="stats-share"><span class="stats-share-bar" aria-hidden="true"><span style="width:${(row.share * 100).toFixed(2)}%"></span></span>${percent(row.share)}</td>`;
}

function ranking(view: StatsView, mode: View): string {
  const period = PERIODS.find((p) => p.id === view.period)!;
  const rows = rankRows(view, mode);
  if (mode === 'groups') {
    const body = rows
      .map(
        (row, i) => `
        <tr data-row="${escapeHtml(row.key)}">
          <td class="stats-rank">${i + 1}</td>
          <th scope="row"><span class="stats-swatch stats-seg--${row.group}" aria-hidden="true"></span>${escapeHtml(row.label)}
            <span class="stats-hint">${escapeHtml(GROUPS.find((g) => g.id === row.group)!.hint)}</span></th>
          <td>${numbers.format(row.count)}</td>
          ${shareCell(row)}
          ${changeCell(row)}
        </tr>`,
      )
      .join('');
    const details = rows
      .filter((row) => row.count > 0)
      .map((row) => {
        const routes = rankRows(view, 'routes').filter((r) => r.group === row.group);
        return `
        <details class="stats-group-routes">
          <summary>${escapeHtml(row.label)}: ${routes.length} route${routes.length === 1 ? '' : 's'}</summary>
          <ul>${routes
            .map(
              (r) =>
                `<li><code>${escapeHtml(r.label)}</code><span>${numbers.format(r.count)}</span></li>`,
            )
            .join('')}</ul>
        </details>`;
      })
      .join('');
    return `
      <section class="stats-section" aria-labelledby="stats-ranking">
        <h2 class="stats-heading" id="stats-ranking">Groups</h2>
        <div class="sw-table-wrap">
          <table class="sw-table stats-table stats-ranking">
            <thead><tr><th scope="col">#</th><th scope="col">Group</th><th scope="col">Requests</th><th scope="col">Share</th><th scope="col">vs ${escapeHtml(period.previous)}</th></tr></thead>
            <tbody>${body}</tbody>
          </table>
        </div>
        ${details}
      </section>`;
  }
  const body = rows
    .map(
      (row, i) => `
      <tr data-row="${escapeHtml(row.key)}">
        <td class="stats-rank">${i + 1}</td>
        <th scope="row"><code>${escapeHtml(row.label)}</code></th>
        <td><span class="stats-tag"><span class="stats-swatch stats-seg--${row.group}" aria-hidden="true"></span>${escapeHtml(groupLabel(row.group))}</span></td>
        <td>${numbers.format(row.count)}</td>
        ${shareCell(row)}
        ${changeCell(row)}
      </tr>`,
    )
    .join('');
  return `
    <section class="stats-section" aria-labelledby="stats-ranking">
      <h2 class="stats-heading" id="stats-ranking">Top routes</h2>
      ${
        rows.length
          ? `<div class="sw-table-wrap">
          <table class="sw-table stats-table stats-ranking">
            <thead><tr><th scope="col">#</th><th scope="col">Route</th><th scope="col">Group</th><th scope="col">Requests</th><th scope="col">Share</th><th scope="col">vs ${escapeHtml(period.previous)}</th></tr></thead>
            <tbody>${body}</tbody>
          </table>
        </div>`
          : '<p class="stats-empty">No requests counted in this window yet.</p>'
      }
    </section>`;
}

/** "0.42", "3.17", "45.3", "1,204"; "—" when there is no value. */
export function formatMs(value: number | undefined | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) return '—';
  if (value < 10) return value.toFixed(2);
  if (value < 100) return value.toFixed(1);
  return numbers.format(Math.round(value));
}

export interface LatencyRow {
  key: string;
  label: string;
  group: GroupId;
  latency: Latency;
}

/** Routes that had timed requests, or all five groups, by timed requests. */
export function latencyRows(
  perf: Performance,
  routeGroups: Record<string, GroupId>,
  mode: View,
): LatencyRow[] {
  if (mode === 'groups') {
    return GROUPS.map((g) => ({
      key: g.id,
      label: g.label,
      group: g.id,
      latency: perf.groups[g.id] ?? { count: 0 },
    })).sort((a, b) => b.latency.count - a.latency.count);
  }
  return Object.entries(perf.routes)
    .filter(([, latency]) => latency.count > 0)
    .map(([route, latency]) => ({
      key: route,
      label: route,
      group: routeGroups[route] ?? 'other',
      latency,
    }))
    .sort((a, b) => b.latency.count - a.latency.count || a.key.localeCompare(b.key));
}

/**
 * SVG path for one percentile line in a 0..n by 0..100 box. Hours or days
 * without timed requests break the line; a lone point is drawn as a dot
 * (a zero-length segment with round caps).
 */
export function latencyPath(points: (number | undefined)[], max: number): string {
  const parts: string[] = [];
  let open = false;
  points.forEach((value, i) => {
    if (value === undefined || value === null) {
      open = false;
      return;
    }
    const x = (i + 0.5).toFixed(2);
    const y = (100 - (Math.min(value, max) / max) * 100).toFixed(2);
    const next = points[i + 1];
    if (open) {
      parts.push(`L${x} ${y}`);
    } else {
      parts.push(`M${x} ${y}`);
      if (next === undefined || next === null) parts.push('l0 0');
    }
    open = true;
  });
  return parts.join(' ');
}

function latencyChart(view: StatsView, perf: Performance): string {
  const unit = view.granularity === 'hour' ? 'hour' : 'day';
  const series = perf.series;
  const timed = series.filter((p) => p.count > 0);
  const top = Math.max(0, ...timed.map((p) => p.p95 ?? 0));
  const max = top > 0 ? top : 1;
  const n = Math.max(1, series.length);
  const p50 = latencyPath(
    series.map((p) => p.p50),
    max,
  );
  const p95 = latencyPath(
    series.map((p) => p.p95),
    max,
  );
  const first = series[0];
  const middle = series[Math.floor(series.length / 2)];
  const last = series[series.length - 1];
  const axis = [first, middle, last]
    .map((p) => `<span>${escapeHtml(p ? bucketLabel(p.start, view.granularity) : '')}</span>`)
    .join('');
  const label = timed.length
    ? `p50 and p95 server time per ${unit}, all routes: highest p95 ${formatMs(top)} ms`
    : `p50 and p95 server time per ${unit}: no timed requests yet`;
  return `
    <div class="stats-latency">
      <ul class="stats-latency-legend">
        <li><span class="stats-swatch stats-swatch--p50" aria-hidden="true"></span>p50 (median)</li>
        <li><span class="stats-swatch stats-swatch--p95" aria-hidden="true"></span>p95</li>
      </ul>
      <div class="stats-latency-plot">
        <span class="stats-latency-max" aria-hidden="true">${escapeHtml(timed.length ? `${formatMs(top)} ms` : '')}</span>
        <svg class="stats-latency-svg" viewBox="0 0 ${n} 100" preserveAspectRatio="none" role="img"
          aria-label="${escapeHtml(label)}">
          <path class="stats-line stats-line--p95" d="${p95}"></path>
          <path class="stats-line stats-line--p50" d="${p50}"></path>
        </svg>
      </div>
      <div class="stats-axis" aria-hidden="true">${axis}</div>
      <details class="stats-numbers">
        <summary>Show the numbers</summary>
        <div class="sw-table-wrap">
          <table class="sw-table stats-table">
            <thead><tr><th scope="col">${unit === 'hour' ? 'Hour (UTC)' : 'Day (UTC)'}</th><th scope="col">Requests</th><th scope="col">p50 (ms)</th><th scope="col">p95 (ms)</th></tr></thead>
            <tbody>${series
              .map(
                (p) =>
                  `<tr><td>${escapeHtml(bucketLabel(p.start, view.granularity))}</td><td>${numbers.format(p.count)}</td><td>${formatMs(p.p50)}</td><td>${formatMs(p.p95)}</td></tr>`,
              )
              .join('')}</tbody>
          </table>
        </div>
      </details>
    </div>`;
}

function performance(view: StatsView, mode: View): string {
  const perf = view.performance;
  const intro = `<p class="stats-note">Server time, network excluded: from when the server receives a
    request until its response is written, in milliseconds. p50, p95 and p99 are estimated from
    fixed buckets, within 9%.</p>`;
  if (!perf) {
    return `
      <section class="stats-section" aria-labelledby="stats-performance">
        <h2 class="stats-heading" id="stats-performance">Performance</h2>
        <p class="stats-empty">Server times are not available from this server yet.</p>
      </section>`;
  }
  const rows = latencyRows(perf, view.routeGroups, mode);
  const cells = (l: Latency) =>
    [l.mean, l.min, l.max, l.stddev, l.p50, l.p95, l.p99]
      .map((v) => `<td class="stats-ms">${formatMs(v)}</td>`)
      .join('');
  const body = rows
    .map((row) => {
      const name =
        mode === 'groups'
          ? `<th scope="row"><span class="stats-swatch stats-seg--${row.group}" aria-hidden="true"></span>${escapeHtml(row.label)}</th>`
          : `<th scope="row"><code>${escapeHtml(row.label)}</code></th>`;
      return `
        <tr data-latency="${escapeHtml(row.key)}">
          ${name}
          <td>${numbers.format(row.latency.count)}</td>
          ${cells(row.latency)}
        </tr>`;
    })
    .join('');
  const table = rows.length
    ? `<div class="sw-table-wrap">
        <table class="sw-table stats-table stats-latency-table">
          <thead><tr><th scope="col">${mode === 'groups' ? 'Group' : 'Route'}</th><th scope="col">Requests</th><th scope="col">Mean</th><th scope="col">Min</th><th scope="col">Max</th><th scope="col">Std dev</th><th scope="col">p50</th><th scope="col">p95</th><th scope="col">p99</th></tr></thead>
          <tbody>${body}</tbody>
        </table>
      </div>`
    : '<p class="stats-empty">No timed requests in this window yet.</p>';
  return `
    <section class="stats-section" aria-labelledby="stats-performance">
      <h2 class="stats-heading" id="stats-performance">Performance</h2>
      ${intro}
      ${table}
      ${latencyChart(view, perf)}
    </section>`;
}

/** The data part of the page for one window and one view. */
export function renderStatsData(view: StatsView, mode: View): string {
  return `${kpis(view, mode)}${chart(view, mode)}${ranking(view, mode)}${performance(view, mode)}`;
}

function toggles<T extends string>(
  name: string,
  label: string,
  options: { id: T; label: string }[],
  selected: T,
): string {
  return `
    <div class="stats-filter" role="group" aria-label="${escapeHtml(label)}">
      <span class="stats-filter-label" aria-hidden="true">${escapeHtml(label)}</span>
      <div class="sw-tabs">${options
        .map(
          (o) =>
            `<button type="button" class="sw-tab${o.id === selected ? ' active' : ''}" data-${name}="${escapeHtml(o.id)}" aria-pressed="${o.id === selected}">${escapeHtml(o.label)}</button>`,
        )
        .join('')}</div>
    </div>`;
}

/**
 * Renders the page and loads the selected window.
 *
 * @param isCurrent false once the user navigated elsewhere; late responses are then dropped
 */
export async function renderStats(
  container: HTMLElement,
  isCurrent: () => boolean = () => true,
): Promise<void> {
  const filters = parseFilters(window.location.search);
  const cache = new Map<Period, StatsView>();
  let generation = 0;

  container.innerHTML = `
    <div class="stats sw-inner-wide">
      <h1 class="sw-page-title">Usage stats</h1>
      <p class="stats-intro">
        Anonymous request counts for the REST API, the MCP server and this site, per route.
        No IP addresses, no cookies, nothing about who sent a request
        (<a href="/privacy">privacy policy</a>). Times are UTC; the raw numbers are at
        <a href="/api/stats">/api/stats</a>.
      </p>
      <div class="stats-filters">
        <div data-filter="period"></div>
        <div data-filter="view"></div>
      </div>
      <div class="stats-data" aria-live="polite"></div>
    </div>`;
  const data = container.querySelector<HTMLElement>('.stats-data')!;
  const periodSlot = container.querySelector<HTMLElement>('[data-filter="period"]')!;
  const viewSlot = container.querySelector<HTMLElement>('[data-filter="view"]')!;

  const drawFilters = () => {
    periodSlot.innerHTML = toggles('period', 'Period', PERIODS, filters.period);
    viewSlot.innerHTML = toggles('view', 'Group by', VIEWS, filters.view);
  };

  const syncUrl = () => {
    const url = `${window.location.pathname}${filtersQuery(filters)}`;
    if (url !== `${window.location.pathname}${window.location.search}`) {
      history.replaceState(history.state, '', url);
    }
  };

  // The navigation check from main.ts only guards the first load: main.ts also
  // bumps its generation on every button click inside #main-content (the
  // ResourceExplorer's "human action"), so after a filter click it would read
  // as "navigated away" and drop the response, leaving the spinner forever.
  // Later loads check that this page is still the one rendered instead.
  let firstLoad = true;
  const stillHere = () => (firstLoad ? isCurrent() : true) && container.contains(data);

  const load = async () => {
    const mine = ++generation;
    const cached = cache.get(filters.period);
    if (cached) {
      data.removeAttribute('aria-busy');
      data.innerHTML = renderStatsData(cached, filters.view);
      return;
    }
    data.setAttribute('aria-busy', 'true');
    data.innerHTML = '<div class="loading"><div class="spinner" aria-label="Loading"></div></div>';
    try {
      const { data: view } = await fetchStats<StatsView>(filters.period);
      if (mine !== generation || !stillHere()) return;
      cache.set(view.period, view);
      data.removeAttribute('aria-busy');
      data.innerHTML = renderStatsData(view, filters.view);
    } catch (err) {
      if (mine !== generation || !stillHere()) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      data.removeAttribute('aria-busy');
      const detail = err instanceof ApiError ? err.message : 'Unexpected response';
      data.innerHTML = `
        <div class="error-message stats-error" role="alert">
          <p>The stats could not be loaded (${escapeHtml(detail)}).</p>
          <button type="button" class="sw-pill sw-pill--ghost sw-pill--sm" data-retry>Try again</button>
        </div>`;
    }
  };

  // On the page's own root, not on the long-lived #main-content: the listener
  // goes away with the page instead of piling up on every visit.
  const root = container.querySelector<HTMLElement>('.stats')!;
  root.addEventListener('click', (event) => {
    const target = (event.target as Element).closest<HTMLElement>(
      '[data-period], [data-view], [data-retry]',
    );
    if (!target || !root.contains(target)) return;
    firstLoad = false;
    if (target.dataset.period) {
      const period = PERIODS.find((p) => p.id === target.dataset.period)?.id;
      if (!period || period === filters.period) return;
      filters.period = period;
    } else if (target.dataset.view) {
      const view = VIEWS.find((v) => v.id === target.dataset.view)?.id;
      if (!view || view === filters.view) return;
      filters.view = view;
    }
    drawFilters();
    syncUrl();
    void load();
    // Keep keyboard focus on the button that was pressed (it was re-rendered).
    const selector = target.dataset.period
      ? `[data-period="${filters.period}"]`
      : target.dataset.view
        ? `[data-view="${filters.view}"]`
        : null;
    if (selector) root.querySelector<HTMLElement>(selector)?.focus();
  });

  drawFilters();
  syncUrl();
  await load();
  firstLoad = false;
}
