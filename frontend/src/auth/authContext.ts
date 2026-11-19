import { createContext, useContext } from 'react';
import type { LoginRequest } from '../api/auth';
import type { Role, SessionUser } from './tokenStore';

/** `restoring` until the silent refresh on page load has answered. */
export type AuthStatus = 'restoring' | 'signedOut' | 'signedIn';

export interface AuthContextValue {
  status: AuthStatus;
  /** Decoded from the JWT for the UI only; the server decides every permission. */
  user: SessionUser | null;
  login: (credentials: LoginRequest) => Promise<SessionUser>;
  /** Ends the session (server and local) and goes to /login. Never throws. */
  logout: () => Promise<void>;
  hasRole: (role: Role) => boolean;
}

/** Router state handed to /login. Kept in history state, never in the URL. */
export interface LoginLocationState {
  /** The server ended the session: show "Your session has ended". */
  sessionEnded?: boolean;
  /** Where to return after signing in. */
  from?: string;
}

export const SESSION_ENDED_MESSAGE = 'Your session has ended. Please sign in again.';

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return value;
}
