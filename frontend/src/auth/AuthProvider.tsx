import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import * as authApi from '../api/auth';
import { AuthContext, loginStateFor, type AuthContextValue } from './authContext';
import { refreshAccessToken } from './refresh';
import {
  acknowledgeSessionEnd,
  getSessionSnapshot,
  onSessionEnded,
  subscribe,
  type Role,
} from './tokenStore';

/**
 * Session state for the app (documentations/08 §4D D3). On mount it runs ONE silent refresh to
 * restore a session after a reload (guarded, so React StrictMode's double effect never sends a
 * second refresh, which would count as token reuse). When a session ends (the server ended it,
 * or a password change ended every session) it navigates to /login with the matching notice in
 * router state; RequireAuth computes the same state from the same reason (loginStateFor).
 * Must be rendered inside a router.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const session = useSyncExternalStore(subscribe, getSessionSnapshot);
  const [restoreDone, setRestoreDone] = useState(() => getSessionSnapshot() !== null);
  const restoreStarted = useRef(false);
  const navigate = useNavigate();
  const location = useLocation();
  const pathnameRef = useRef(location.pathname);

  useEffect(() => {
    pathnameRef.current = location.pathname;
    // Once the user leaves /login, the end reason's notice has served its purpose.
    if (location.pathname !== '/login') {
      acknowledgeSessionEnd();
    }
  }, [location.pathname]);

  useEffect(() => {
    if (restoreStarted.current || getSessionSnapshot() !== null) {
      return;
    }
    restoreStarted.current = true;
    refreshAccessToken()
      .catch(() => 'signedOut' as const)
      .finally(() => setRestoreDone(true));
  }, []);

  useEffect(
    () =>
      onSessionEnded((reason) => {
        navigate('/login', { replace: true, state: loginStateFor(reason, pathnameRef.current) });
      }),
    [navigate],
  );

  const login = useCallback(async (credentials: authApi.LoginRequest) => {
    const snapshot = await authApi.login(credentials);
    setRestoreDone(true);
    return snapshot.user;
  }, []);

  const logout = useCallback(async () => {
    await authApi.logout();
    navigate('/login', { replace: true });
  }, [navigate]);

  const value = useMemo<AuthContextValue>(() => {
    const user = session?.user ?? null;
    return {
      status: user ? 'signedIn' : restoreDone ? 'signedOut' : 'restoring',
      user,
      login,
      logout,
      hasRole: (role: Role) => user?.roles.includes(role) ?? false,
    };
  }, [session, restoreDone, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
