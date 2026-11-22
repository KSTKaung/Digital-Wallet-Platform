import type { ReactNode } from 'react';
import { Outlet } from 'react-router';
import { useAuth } from './authContext';
import { RequireAuth } from './RequireAuth';
import type { Role } from './tokenStore';

/**
 * UI guard only (doc 05 §7.1: items a role may not use are hidden). Requires a session and one of
 * `roles`; the server still refuses any direct call the role is not allowed to make.
 */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children?: ReactNode }) {
  return (
    <RequireAuth>
      <RoleCheck roles={roles}>{children}</RoleCheck>
    </RequireAuth>
  );
}

function RoleCheck({ roles, children }: { roles: readonly Role[]; children?: ReactNode }) {
  const { hasRole } = useAuth();
  if (!roles.some(hasRole)) {
    return (
      <section aria-labelledby="no-access-heading" className="space-y-2">
        <h1 id="no-access-heading" className="text-xl font-semibold">
          Not available
        </h1>
        <p>This page is not available for your account.</p>
      </section>
    );
  }
  return children ?? <Outlet />;
}
