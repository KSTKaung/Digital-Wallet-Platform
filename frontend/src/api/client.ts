// Thin fetch wrapper for the Gateway API (doc 02 §7). Typed endpoint modules
// (auth.ts, wallet.ts, ...) build on this; generated OpenAPI types replace the
// hand-written ones once the spec is in the repo.

/** Error body returned by every service (doc 02 §1.7). */
export interface ApiErrorBody {
  success: false;
  errorCode: string;
  message: string;
  correlationId?: string;
  timestamp?: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly errorCode: string,
    message: string,
    readonly correlationId?: string,
    /** Seconds from `Retry-After` on 429 / 503 (doc 02 §1.3). */
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** Absolute API origin, or '' for same-origin requests through the dev proxy / nginx. */
export function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  // Internal service APIs are never reachable from the browser (doc 02 §1.1, §7).
  if (path.startsWith('/internal')) {
    throw new Error(`Refusing to call internal path ${path}`);
  }

  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(`${apiBaseUrl()}${path}`, { ...init, headers });

  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  const retryAfter = parseRetryAfter(response.headers.get('Retry-After'));
  const headerCorrelationId = response.headers.get('X-Correlation-Id') ?? undefined;

  let body: Partial<ApiErrorBody> | undefined;
  try {
    body = (await response.json()) as Partial<ApiErrorBody>;
  } catch {
    body = undefined;
  }

  return new ApiError(
    response.status,
    body?.errorCode ?? 'SYSTEM_ERROR',
    body?.message ?? `Request failed with status ${response.status}`,
    body?.correlationId ?? headerCorrelationId,
    retryAfter,
  );
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}
