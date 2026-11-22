import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { loginStateFor, useAuth } from './authContext';
import { getSessionEndReason } from './tokenStore';

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
 * When a session just ended, the /login state comes from the recorded end reason, the same as
 * the AuthProvider's navigation, so the two can run in either order.
 */
export function RequireAuth({ children }: { children?: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'restoring') {
    return <SessionRestoring />;
  }
  if (status === 'signedOut') {
    return (
      <Navigate
        to="/login"
        replace
        state={loginStateFor(getSessionEndReason(), location.pathname)}
      />
    );
  }
  return children ?? <Outlet />;
}
