import { afterEach, expect, it, vi } from 'vitest';
import { cancelPending, fetchResources, fetchStats } from './api';

afterEach(() => {
  cancelPending();
  vi.unstubAllGlobals();
});

it('keeps explicitly scoped requests independent of navigation cancellation', async () => {
  const signals: AbortSignal[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((_url, options) => {
      signals.push(options.signal);
      return Promise.resolve(new Response('[]'));
    }),
  );
  const first = new AbortController();
  const second = new AbortController();
  const a = fetchResources('people', first.signal);
  const b = fetchResources('films', second.signal);
  cancelPending();
  expect(signals.every((signal) => !signal.aborted)).toBe(true);
  await expect(a).resolves.toEqual({ data: [], status: 200 });
  await expect(b).resolves.toEqual({ data: [], status: 200 });
});

it('lets a stats request opt out of navigation cancellation with its own signal', async () => {
  // The home page's live line passes its own signal: a terminal query on the
  // same page (which cancels pending requests) must not abort it.
  const signals: AbortSignal[] = [];
  const urls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url, options) => {
      urls.push(url);
      signals.push(options.signal);
      return Promise.resolve(new Response('{"total":3}'));
    }),
  );
  const own = new AbortController();
  const pending = fetchStats('24h', own.signal);
  cancelPending();
  expect(signals[0].aborted).toBe(false);
  expect(urls).toEqual(['/api/stats?period=24h']);
  await expect(pending).resolves.toEqual({ data: { total: 3 }, status: 200 });
});
