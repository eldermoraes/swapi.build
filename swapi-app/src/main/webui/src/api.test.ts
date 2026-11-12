import { afterEach, expect, it, vi } from 'vitest';
import { ApiError, cancelPending, fetchResources, fetchStats } from './api';

afterEach(() => {
  cancelPending();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// Issue #71: a request to /api that does not answer ends with an error.

/** A fetch that never answers; it rejects when its signal aborts, as the browser's does. */
function hangingFetch() {
  return vi.fn(
    (_url: string, options: { signal: AbortSignal }) =>
      new Promise<Response>((_resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(options.signal.reason));
      }),
  );
}

/** Replaces the browser's timer: `expire` is the time running out. */
function controlTimeout(): { limits: number[]; expire: () => void } {
  const timer = new AbortController();
  const limits: number[] = [];
  vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms) => {
    limits.push(ms);
    return timer.signal;
  });
  return {
    limits,
    expire: () => timer.abort(new DOMException('The operation timed out.', 'TimeoutError')),
  };
}

it('a request that does not answer in 10 seconds ends with an error', async () => {
  const timeout = controlTimeout();
  vi.stubGlobal('fetch', hangingFetch());
  const outcome = fetchResources('people').catch((error: unknown) => error);
  timeout.expire();

  const error = await outcome;
  expect(error).toBeInstanceOf(ApiError);
  expect(error).toMatchObject({ type: 'network', status: 0 });
  expect((error as ApiError).message).toMatch(/too long/);
  expect(timeout.limits).toEqual([10_000]);
});

it('a request with its own signal has the time limit too', async () => {
  const timeout = controlTimeout();
  vi.stubGlobal('fetch', hangingFetch());
  const own = new AbortController();
  const outcome = fetchStats('24h', own.signal).catch((error: unknown) => error);
  timeout.expire();

  expect(await outcome).toMatchObject({ name: 'ApiError', type: 'network' });
  expect(own.signal.aborted).toBe(false);
});

it('a cancelled request still ends with the reason of the cancellation, not a timeout', async () => {
  controlTimeout();
  vi.stubGlobal('fetch', hangingFetch());
  const own = new AbortController();
  const outcome = fetchResources('people', own.signal).catch((error: unknown) => error);
  own.abort('SUPERSEDED');

  expect(await outcome).toBe('SUPERSEDED');
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
