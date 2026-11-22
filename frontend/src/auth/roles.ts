// Role groups for the UI only (doc 05 §7.1: items a role may not use are hidden). The server
// authorises every request (SecurityConfig: /admin/kyc/** and /admin/transfer-reviews/** for
// COMPLIANCE_OFFICER, /admin/customers/** for ADMIN).

import type { Role } from './tokenStore';

/** The back-office roles; staff and customer accounts are separate (MVP-ADR-12). */
export const STAFF_ROLES: readonly Role[] = ['COMPLIANCE_OFFICER', 'ADMIN'];

export const CUSTOMER_ROLES: readonly Role[] = ['CUSTOMER'];

/** A staff account: a staff role and no customer role. */
export function isStaff(roles: readonly Role[]): boolean {
  return !roles.includes('CUSTOMER') && roles.some((role) => STAFF_ROLES.includes(role));
}
