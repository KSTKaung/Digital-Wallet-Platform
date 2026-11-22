import { createContext, useContext } from 'react';
import type { LoginRequest } from '../api/auth';
import type { Role, SessionEndReason, SessionUser } from './tokenStore';

/** `restoring` until the silent refresh on page load has answered. */
export type AuthStatus = 'restoring' | 'signedOut' | 'signedIn';

export interface AuthContextValue {
  status: AuthStatus;
  /** Decoded from the JWT for the UI only; the server decides every permission. */
  user: SessionUser | null;
  login: (credentials: LoginRequest) => Promise<SessionUser>;
  /**
   * Ends the session (server and local) and goes to /login; when the server did not confirm it,
   * /login shows the "could not be confirmed" alert. Never throws.
   */
  logout: () => Promise<void>;
  hasRole: (role: Role) => boolean;
}

/** Why /login is shown after a completed flow; the login page shows the matching text. */
export type LoginNotice = 'passwordChanged' | 'passwordReset';

/** Router state handed to /login. Kept in history state, never in the URL. */
export interface LoginLocationState {
  /** The server ended the session: show "Your session has ended". */
  sessionEnded?: boolean;
  /** Sign-out cleared this tab, but the server did not confirm it (the cookie may be valid). */
  logoutUnconfirmed?: boolean;
  /** A completed flow (password changed or reset). */
  notice?: LoginNotice;
  /** Where to return after signing in; checked with safeRedirectPath before use. */
  from?: string;
}

export const SESSION_ENDED_MESSAGE = 'Your session has ended. Please sign in again.';

export const LOGOUT_UNCONFIRMED_MESSAGE =
  'Sign-out could not be confirmed, so you may still be signed in on this browser.';

/** How to "try again" once this tab is already signed out (the next load may restore the session). */
export const LOGOUT_UNCONFIRMED_GUIDANCE =
  'To try again, reload this page; if you are still signed in, choose Sign out.';

export const LOGIN_NOTICE_MESSAGES: Readonly<Record<LoginNotice, string>> = {
  passwordChanged: 'Password changed. Sign in again.',
  passwordReset: 'Password reset. Sign in with your new password.',
};

/**
 * Router state for /login after a session ended, from the reason recorded in the token store.
 * The AuthProvider listener and the RequireAuth guard both use it, so whichever navigation runs
 * last, /login gets the same state (no render race).
 */
export function loginStateFor(
  reason: SessionEndReason | null,
  from: string,
): LoginLocationState | undefined {
  switch (reason) {
    case 'passwordChanged':
      return { notice: 'passwordChanged' };
    case 'sessionEnded':
      return { sessionEnded: true, from };
    case 'loggedOut':
      return undefined;
    case 'logoutUnconfirmed':
      return { logoutUnconfirmed: true };
    default:
      return { from };
  }
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return value;
}
