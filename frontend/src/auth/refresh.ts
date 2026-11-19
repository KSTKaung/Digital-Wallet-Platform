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
import { clearSession, setSession } from './tokenStore';

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
 * so nothing is cleared.
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
    clearSession();
    return 'signedOut';
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
  if (!body || typeof body.accessToken !== 'string') {
    clearSession();
    return 'signedOut';
  }
  return setSession(body.accessToken, Number(body.expiresIn)) ? 'refreshed' : 'signedOut';
}
