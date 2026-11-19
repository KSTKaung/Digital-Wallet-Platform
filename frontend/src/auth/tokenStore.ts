// In-memory session state (documentations/06 MVP-ADR-14, documentations/08 §4D D3).
//
// The access token lives only in this module's memory: never in localStorage, sessionStorage,
// cookies, the URL or React state. The refresh token is an HttpOnly cookie the browser manages;
// JavaScript never sees it. Roles and subject are decoded from the JWT payload for the UI only
// (navigation, guards). They are never verified here and never trusted for security: the server
// decides every permission.

export type Role = 'CUSTOMER' | 'ADMIN' | 'COMPLIANCE_OFFICER';

const KNOWN_ROLES: ReadonlySet<string> = new Set<Role>(['CUSTOMER', 'ADMIN', 'COMPLIANCE_OFFICER']);

export interface SessionUser {
  /** JWT `sub` (the user id). */
  readonly sub: string;
  /** JWT `roles`, limited to the known role names. */
  readonly roles: readonly Role[];
}

/** What React may see: no token, only the user and the expiry. */
export interface SessionSnapshot {
  readonly user: SessionUser;
  /** Epoch milliseconds after which the access token is treated as expired. */
  readonly expiresAt: number;
}

type Listener = () => void;

/**
 * Why the last session ended, so every part of the app that sends the user to /login (the
 * AuthProvider listener and the RequireAuth guard) picks the same router state, whichever runs
 * first. `sessionEnded`: the server ended it (second 401, refused refresh). `passwordChanged`:
 * change-password ended every session. `loggedOut`: the user signed out.
 */
export type SessionEndReason = 'sessionEnded' | 'passwordChanged' | 'loggedOut';

/** The reasons that emit the session-ended event (a chosen sign-out does not). */
export type EndedReason = Exclude<SessionEndReason, 'loggedOut'>;

type EndedListener = (reason: EndedReason) => void;

let accessToken: string | null = null;
let snapshot: SessionSnapshot | null = null;
let endReason: SessionEndReason | null = null;
const changeListeners = new Set<Listener>();
const endedListeners = new Set<EndedListener>();

function emit(listeners: Set<Listener>): void {
  for (const listener of [...listeners]) {
    listener();
  }
}

/**
 * Decodes the JWT payload without verifying it. Returns null for anything malformed (wrong part
 * count, bad base64url, bad JSON, missing `sub`), so a broken token is treated as no session.
 */
export function decodeJwtClaims(token: string): SessionUser | null {
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) {
    return null;
  }
  let payload: unknown;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
    payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    return null;
  }
  if (typeof payload !== 'object' || payload === null) {
    return null;
  }
  const claims = payload as { sub?: unknown; roles?: unknown };
  if (typeof claims.sub !== 'string' || claims.sub.length === 0) {
    return null;
  }
  const roles = Array.isArray(claims.roles)
    ? claims.roles.filter((r): r is Role => typeof r === 'string' && KNOWN_ROLES.has(r))
    : [];
  return { sub: claims.sub, roles };
}

/**
 * Stores a new access token. A token that cannot be decoded clears the session instead and
 * returns null. `expiresInSeconds` comes from the login/refresh response (`expiresIn`).
 */
export function setSession(token: string, expiresInSeconds: number): SessionSnapshot | null {
  const user = decodeJwtClaims(token);
  if (!user) {
    clearSession();
    return null;
  }
  const lifetimeMs =
    Number.isFinite(expiresInSeconds) && expiresInSeconds > 0 ? expiresInSeconds * 1000 : 0;
  accessToken = token;
  snapshot = { user, expiresAt: Date.now() + lifetimeMs };
  endReason = null;
  emit(changeListeners);
  return snapshot;
}

/**
 * Forgets the token without the session-ended event (failed restore, logout). The reason is
 * recorded before listeners run, so a render triggered by this change already sees it; a call
 * without a reason resets it.
 */
export function clearSession(reason: SessionEndReason | null = null): void {
  endReason = reason;
  if (accessToken === null && snapshot === null) {
    return;
  }
  accessToken = null;
  snapshot = null;
  emit(changeListeners);
}

/**
 * A session this tab was using has ended: by the server (second 401, failed refresh after a
 * 401) or by a password change. Clears the token, records the reason and tells listeners, so
 * the app can go to /login with the matching notice.
 */
export function endSession(reason: EndedReason = 'sessionEnded'): void {
  clearSession(reason);
  for (const listener of [...endedListeners]) {
    listener(reason);
  }
}

/** The reason the last session ended, or null (never signed in, signed in again, or acknowledged). */
export function getSessionEndReason(): SessionEndReason | null {
  return endReason;
}

/** Forgets the end reason once its notice has been shown and the user moved on. */
export function acknowledgeSessionEnd(): void {
  endReason = null;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getSessionSnapshot(): SessionSnapshot | null {
  return snapshot;
}

/** True when a token exists and is not within `skewMs` of its expiry. */
export function hasFreshAccessToken(skewMs = 30_000): boolean {
  return accessToken !== null && snapshot !== null && Date.now() + skewMs < snapshot.expiresAt;
}

/** For useSyncExternalStore: called whenever the token is set or cleared. */
export function subscribe(listener: Listener): () => void {
  changeListeners.add(listener);
  return () => {
    changeListeners.delete(listener);
  };
}

/** Called with the reason when a session ended (see endSession). */
export function onSessionEnded(listener: EndedListener): () => void {
  endedListeners.add(listener);
  return () => {
    endedListeners.delete(listener);
  };
}
