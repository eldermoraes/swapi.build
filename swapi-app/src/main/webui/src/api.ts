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

async function request<T>(url: string): Promise<ApiResponse<T>> {
  cancelPending();
  currentController = new AbortController();

  let res: Response;
  try {
    res = await fetch(url, { signal: currentController.signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw err;
    }
    throw new ApiError('Network error — check your connection', 0, 'network');
  } finally {
    currentController = null;
  }

  if (!res.ok) {
    throw new ApiError(`HTTP ${res.status}: ${res.statusText}`, res.status, 'http');
  }
  return { data: (await res.json()) as T, status: res.status };
}

export async function fetchResources<T = unknown>(type: string): Promise<ApiResponse<T[]>> {
  return request<T[]>(`${BASE}/${type}`);
}

export async function fetchResourceById<T = unknown>(type: string, id: string): Promise<ApiResponse<T>> {
  return request<T>(`${BASE}/${type}/${id}`);
}

export async function searchResource<T = unknown>(type: string, query: string): Promise<ApiResponse<T[]>> {
  return request<T[]>(`${BASE}/${type}?search=${encodeURIComponent(query)}`);
}

export async function fetchRandom<T = unknown>(type: string): Promise<ApiResponse<T>> {
  return request<T>(`${BASE}/${type}/random`);
}

export async function fetchEndpoint<T = unknown>(path: string): Promise<ApiResponse<T>> {
  const url = path.startsWith('/') ? path : `${BASE}/${path}`;
  return request<T>(url);
}
