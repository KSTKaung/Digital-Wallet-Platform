// Silent access-token refresh (documentations/08 §4D D3, doc 06 MVP-ADR-14).
//
// The refresh cookie rotates on every use with no grace period: two refreshes sent with the same
// cookie count as reuse and the server revokes the whole session. So:
// - inside a tab, concurrent callers share one in-flight promise;
// - across tabs, every refresh runs under one exclusive Web Lock, so the next tab's refresh is
//   sent only after the previous one stored the rotated cookie (the cookie jar is shared, so
//   sequential refreshes are valid rotations). Without `navigator.locks` only the in-tab
//   single-flight applies.

import { apiBaseUrl, CORRELATION_ID_HEADER, newUuid, readBody, toApiError } from '../api/http';
import { clearSession, getAccessToken, getSessionGeneration, setSession } from './tokenStore';

export const REFRESH_LOCK_NAME = 'wallet-refresh';
export const REFRESH_PATH = '/api/v1/auth/refresh';

/** `refreshed`: a new token is stored. `signedOut`: the server refused; local state is cleared. */
export type RefreshOutcome = 'refreshed' | 'signedOut';

interface TokenResponse {
  accessToken?: string;
  expiresIn?: number;
}

let inFlight: Promise<RefreshOutcome> | null = null;

/**
 * Refreshes the access token once for all concurrent callers in this tab, under the cross-tab
 * Web Lock. Resolves `signedOut` on 401/403 (never retries, so no loop). Rejects on a network
 * error or another failure status (for example 429 or 5xx): the session state is then unknown,
 * so nothing is cleared. Rejects with UnusableRefreshResponseError on a 2xx without a usable
 * token: the local session is cleared, but the server session may still be alive.
 *
 * An answer applies only to the session the refresh was sent for: if a sign-in or sign-out
 * changed the session while the refresh was in flight (for example a sign-in during a slow
 * restore), a refusal clears nothing and a success stores nothing; the outcome follows the
 * current state (`refreshed` with a token, else `signedOut`), so callers use the current token.
 */
export function refreshAccessToken(): Promise<RefreshOutcome> {
  if (!inFlight) {
    inFlight = runExclusive(sendRefresh).finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

function runExclusive(task: () => Promise<RefreshOutcome>): Promise<RefreshOutcome> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  if (locks && typeof locks.request === 'function') {
    return locks.request(REFRESH_LOCK_NAME, { mode: 'exclusive' }, task);
  }
  return task();
}

async function sendRefresh(): Promise<RefreshOutcome> {
  const startedWith = getSessionGeneration();
  const refused = (): RefreshOutcome => {
    if (getSessionGeneration() !== startedWith) {
      // The session changed while the refresh was in flight: leave the newer state alone.
      return getAccessToken() === null ? 'signedOut' : 'refreshed';
    }
    clearSession();
    return 'signedOut';
  };

  const response = await fetch(`${apiBaseUrl()}${REFRESH_PATH}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      [CORRELATION_ID_HEADER]: newUuid(),
    },
  });

  if (response.status === 401 || response.status === 403) {
    return refused();
  }
  if (!response.ok) {
    throw await toApiError(response);
  }

  let body: TokenResponse | undefined;
  try {
    body = await readBody<TokenResponse | undefined>(response);
  } catch {
    body = undefined;
  }
  if (getSessionGeneration() !== startedWith) {
    // A sign-in or sign-out happened while the refresh was in flight: the newer state wins, and a
    // late answer never replaces it (a cookie of another user is caught by the client's sub check).
    return getAccessToken() === null ? 'signedOut' : 'refreshed';
  }
  // A 2xx means the server accepted and rotated the cookie, so the server session is alive even
  // when the body is unusable. That is "state unknown", not "signed out": the local session is
  // cleared (setSession also clears for an undecodable token) and the call rejects, so logout
  // reports an unconfirmed sign-out instead of a confirmed one (security re-review F1).
  if (!body || typeof body.accessToken !== 'string') {
    clearSession();
    throw new UnusableRefreshResponseError();
  }
  if (!setSession(body.accessToken, Number(body.expiresIn))) {
    throw new UnusableRefreshResponseError();
  }
  return 'refreshed';
}

/** A 2xx refresh answer without a usable access token: the server session may still be alive. */
export class UnusableRefreshResponseError extends Error {
  constructor() {
    super('The refresh answer held no usable access token');
    this.name = 'UnusableRefreshResponseError';
  }
}
