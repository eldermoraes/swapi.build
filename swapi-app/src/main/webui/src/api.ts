import type { OpenApiSpec } from './types';

const BASE = '/api';

let currentController: AbortController | null = null;

export interface ApiResponse<T> {
  data: T;
  status: number;
}

export function cancelPending(): void {
  if (currentController) {
    currentController.abort();
    currentController = null;
  }
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly type: 'network' | 'http',
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(url: string, signal?: AbortSignal): Promise<ApiResponse<T>> {
  const controller = signal ? null : new AbortController();
  if (controller) {
    cancelPending();
    currentController = controller;
  }
  const requestSignal = signal ?? controller!.signal;
  try {
    const res = await fetch(url, { signal: requestSignal });
    if (!res.ok) {
      throw new ApiError(`HTTP ${res.status}: ${res.statusText}`, res.status, 'http');
    }
    const data = (await res.json()) as T;
    requestSignal.throwIfAborted();
    return { data, status: res.status };
  } catch (err) {
    if (requestSignal.aborted) throw requestSignal.reason;
    if (err instanceof ApiError || err instanceof SyntaxError) throw err;
    throw new ApiError('Network error — check your connection', 0, 'network');
  } finally {
    if (controller && currentController === controller) currentController = null;
  }
}

export async function fetchResources<T = unknown>(
  type: string,
  signal?: AbortSignal,
): Promise<ApiResponse<T[]>> {
  return request<T[]>(`${BASE}/${type}`, signal);
}

export async function fetchResourceById<T = unknown>(
  type: string,
  id: string,
  signal?: AbortSignal,
): Promise<ApiResponse<T>> {
  return request<T>(`${BASE}/${type}/${id}`, signal);
}

export async function searchResource<T = unknown>(
  type: string,
  query: string,
  signal?: AbortSignal,
): Promise<ApiResponse<T[]>> {
  return request<T[]>(`${BASE}/${type}?search=${encodeURIComponent(query)}`, signal);
}

export async function fetchRandom<T = unknown>(
  type: string,
  signal?: AbortSignal,
): Promise<ApiResponse<T>> {
  return request<T>(`${BASE}/${type}/random`, signal);
}

export async function fetchEndpoint<T = unknown>(path: string): Promise<ApiResponse<T>> {
  const url = path.startsWith('/') ? path : `${BASE}/${path}`;
  return request<T>(url);
}

// Direct fetch (outside request()): the spec does not take part in navigation
// cancellation and needs an explicit Accept to guarantee JSON.
export async function fetchOpenApiSpec(): Promise<OpenApiSpec> {
  let res: Response;
  try {
    res = await fetch('/openapi.json', { headers: { Accept: 'application/json' } });
  } catch {
    throw new ApiError('Network error — check your connection', 0, 'network');
  }
  if (!res.ok) {
    throw new ApiError(`HTTP ${res.status}: ${res.statusText}`, res.status, 'http');
  }
  return (await res.json()) as OpenApiSpec;
}
