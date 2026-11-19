import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import * as authApi from '../api/auth';
import { AuthContext, type AuthContextValue, type LoginLocationState } from './authContext';
import { refreshAccessToken } from './refresh';
import { getSessionSnapshot, onSessionEnded, subscribe, type Role } from './tokenStore';

/**
 * Session state for the app (documentations/08 §4D D3). On mount it runs ONE silent refresh to
 * restore a session after a reload (guarded, so React StrictMode's double effect never sends a
 * second refresh, which would count as token reuse). When the API client reports that the server
 * ended the session, it navigates to /login with a notice in router state.
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
      onSessionEnded(() => {
        const state: LoginLocationState = { sessionEnded: true, from: pathnameRef.current };
        navigate('/login', { replace: true, state });
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
