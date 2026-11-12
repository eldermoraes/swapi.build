/** A request that has not answered in this time is an error, not a wait forever. */
export const FETCH_TIMEOUT_MS = 10_000;

/**
 * The signal of a request: the caller's plus the timeout. The timeout aborts
 * only the request (reason: a `TimeoutError`); the caller's signal is never
 * aborted by it. A browser without AbortSignal.any or AbortSignal.timeout
 * (Safari before 17.4) keeps the caller's signal and no timeout, rather than
 * failing every request.
 */
export function withTimeout(signal?: AbortSignal, ms = FETCH_TIMEOUT_MS): AbortSignal | undefined {
  if (typeof AbortSignal.timeout !== 'function') return signal;
  if (!signal) return AbortSignal.timeout(ms);
  if (typeof AbortSignal.any !== 'function') return signal;
  return AbortSignal.any([signal, AbortSignal.timeout(ms)]);
}

/** What the page says when the time of a request ran out. */
export const TIMEOUT_MESSAGE = 'The request took too long — try again';

/** True for the error a request rejects with when its time ran out. */
export function isTimeout(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'TimeoutError';
}
