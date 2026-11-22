// Fetch wrapper for the backend API (doc 02 §7, documentations/08 §4D D3). Typed endpoint modules
// (auth.ts, wallet.ts, ...) build on this; types come from the generated schema.d.ts.
//
// - Same-origin by default: relative `/api/v1/...` (VITE_API_BASE_URL is optional).
// - Adds `Authorization: Bearer` from the in-memory token store, an outbound `X-Correlation-Id`
//   and, when given, `Idempotency-Key`.
// - On a 401 it refreshes once (shared with every other caller, see auth/refresh.ts) and retries
//   once with the new token and the same body, headers, correlation id and Idempotency-Key, so a
//   retried money POST is replayed by the server, never executed twice. A second 401, or a
//   refused refresh, ends the session. Public auth endpoints, logout and change-password never
//   refresh (a change-password 401 means a wrong current password).
// - The retry is sent only for the same user: if the token after the refresh (or the one another
//   caller stored) has a different `sub` than the token the request was sent with (another tab
//   signed out and someone else signed in, so the shared cookie now belongs to them), the request
//   is not retried, the session ends and the original error is thrown.

import { refreshAccessToken } from '../auth/refresh';
import { clearSession, decodeJwtClaims, endSession, getAccessToken } from '../auth/tokenStore';
import { CORRELATION_ID_HEADER, newUuid, readBody, toApiError, apiBaseUrl } from './http';

export { ApiError, apiBaseUrl } from './http';
export type { ApiErrorBody } from './http';

/** Public endpoints: no bearer, never refresh (doc 02 §1.2). */
export const PUBLIC_AUTH_PATHS: ReadonlySet<string> = new Set([
  '/api/v1/auth/register',
  '/api/v1/auth/verify-otp',
  '/api/v1/auth/resend-otp',
  '/api/v1/auth/forgot-password',
  '/api/v1/auth/reset-password',
  '/api/v1/auth/login',
  '/api/v1/auth/refresh',
]);

/**
 * Endpoints whose 401 must never trigger refresh-and-retry, whatever the caller asks:
 * the public ones, change-password (401 = wrong current password) and logout (auth.ts runs its
 * own refresh-then-logout sequence, MVP-ADR-23).
 */
export const NO_REFRESH_PATHS: ReadonlySet<string> = new Set([
  ...PUBLIC_AUTH_PATHS,
  '/api/v1/auth/change-password',
  '/api/v1/auth/logout',
]);

export interface ApiRequestInit extends Omit<RequestInit, 'body' | 'credentials'> {
  /** JSON string. Only strings, so a retry can resend the identical body. */
  body?: string;
  /** Send the bearer token. Default: true for `/api/` paths except the public auth endpoints. */
  auth?: boolean;
  /** Refresh once and retry on 401. Default true; ignored (always off) for NO_REFRESH_PATHS. */
  retryOn401?: boolean;
  /** UUID v4 for money POSTs (doc 02 §1.3, §1.4); sent unchanged on the retry. */
  idempotencyKey?: string;
}

function pathOf(path: string): string {
  return path.split(/[?#]/, 1)[0] ?? path;
}

export async function apiRequest<T>(path: string, init: ApiRequestInit = {}): Promise<T> {
  // Internal service APIs are never reachable from the browser (doc 02 §1.1, §7).
  if (path.startsWith('/internal')) {
    throw new Error(`Refusing to call internal path ${path}`);
  }

  const { auth, retryOn401, idempotencyKey, headers: initHeaders, ...rest } = init;
  const plainPath = pathOf(path);
  const useBearer = auth ?? (plainPath.startsWith('/api/') && !PUBLIC_AUTH_PATHS.has(plainPath));
  const mayRefresh = useBearer && retryOn401 !== false && !NO_REFRESH_PATHS.has(plainPath);
  // One logical request keeps one correlation id, also on its retry.
  const correlationId = newUuid();

  const send = (token: string | null): Promise<Response> => {
    const headers = new Headers(initHeaders);
    headers.set('Accept', 'application/json');
    if (rest.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (!headers.has(CORRELATION_ID_HEADER)) {
      headers.set(CORRELATION_ID_HEADER, correlationId);
    }
    if (idempotencyKey !== undefined) {
      headers.set('Idempotency-Key', idempotencyKey);
    }
    if (useBearer && token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    return fetch(`${apiBaseUrl()}${path}`, { ...rest, headers, credentials: 'same-origin' });
  };

  const sentToken = useBearer ? getAccessToken() : null;
  let response = await send(sentToken);

  if (response.status === 401 && mayRefresh) {
    const firstError = await toApiError(response);
    const token = await tokenAfter401(sentToken);
    if (token === null) {
      signOutAfter401(sentToken);
      throw firstError;
    }
    if (sentToken !== null && subjectOf(token) !== subjectOf(sentToken)) {
      // Never replay this user's request (possibly a money POST) as somebody else.
      endSession();
      throw firstError;
    }
    response = await send(token);
    if (response.status === 401) {
      const secondError = await toApiError(response);
      signOutAfter401(token);
      throw secondError;
    }
  }

  if (!response.ok) {
    throw await toApiError(response);
  }
  return readBody<T>(response);
}

/**
 * A token to retry with, or null when the session is gone. If another caller already replaced
 * the token this request was sent with, that newer token is used without another refresh.
 */
async function tokenAfter401(sentToken: string | null): Promise<string | null> {
  const current = getAccessToken();
  if (current !== null && current !== sentToken) {
    return current;
  }
  const outcome = await refreshAccessToken();
  return outcome === 'refreshed' ? getAccessToken() : null;
}

/** The JWT `sub`, or null for a token that cannot be decoded. */
function subjectOf(token: string): string | null {
  return decodeJwtClaims(token)?.sub ?? null;
}

/** Only a tab that had a session hears "session ended"; a tab that never signed in just clears. */
function signOutAfter401(usedToken: string | null): void {
  if (usedToken !== null) {
    endSession();
  } else {
    clearSession();
  }
}
