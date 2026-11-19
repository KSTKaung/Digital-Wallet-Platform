import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth, type LoginLocationState } from './authContext';

/** Shown while the session is being restored; renders nothing from the protected page. */
export function SessionRestoring() {
  return (
    <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
      Checking your session…
    </p>
  );
}

/**
 * UI guard only: the server authorises every request. While the session is restoring nothing
 * of the protected page is rendered; signed-out users go to /login and come back afterwards.
 */
export function RequireAuth({ children }: { children?: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'restoring') {
    return <SessionRestoring />;
  }
  if (status === 'signedOut') {
    const state: LoginLocationState = { from: location.pathname };
    return <Navigate to="/login" replace state={state} />;
  }
  return children ?? <Outlet />;
}
