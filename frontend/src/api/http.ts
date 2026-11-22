// Low-level HTTP helpers shared by client.ts and auth/refresh.ts. Kept separate so the refresh
// code can call the API without importing client.ts (which itself depends on the refresh code).

/** Error body returned by every endpoint (doc 02 §1.7). */
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

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/** Absolute API origin, or '' for same-origin requests through the dev proxy / nginx. */
export function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');
}

/** A fresh UUID v4 (outbound `X-Correlation-Id`, `Idempotency-Key`). */
export function newUuid(): string {
  return crypto.randomUUID();
}

export async function toApiError(response: Response): Promise<ApiError> {
  const retryAfter = parseRetryAfter(response.headers.get('Retry-After'));
  const headerCorrelationId = response.headers.get(CORRELATION_ID_HEADER) ?? undefined;

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

/** Parses a success body: 204 or an empty body (e.g. verify-otp REGISTRATION) gives undefined. */
export async function readBody<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (text.trim() === '') {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}
