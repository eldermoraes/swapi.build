import { afterEach, expect, it, vi } from 'vitest';
import { cancelPending, fetchResources } from './api';

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
