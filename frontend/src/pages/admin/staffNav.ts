// The staff navigation (doc 05 §7.1: items a role may not use are hidden; documentations/08 §4D.3
// routes 6b). An item is listed only once its screen exists, so there are no dead links. UI only:
// the server refuses every call the role may not make.

import type { Role } from '../../auth/tokenStore';

export interface StaffNavItem {
  to: string;
  label: string;
  /** Roles that may use the screen (the same as its route guard). */
  roles: readonly Role[];
}

export const COMPLIANCE_ROLES: readonly Role[] = ['COMPLIANCE_OFFICER'];

export const STAFF_NAV_ITEMS: readonly StaffNavItem[] = [
  { to: '/admin/kyc', label: 'KYC reviews', roles: COMPLIANCE_ROLES },
  { to: '/admin/transfer-reviews', label: 'Transfer reviews', roles: COMPLIANCE_ROLES },
];

/** The items the signed-in user may use, in navigation order. */
export function staffItemsFor(hasRole: (role: Role) => boolean): StaffNavItem[] {
  return STAFF_NAV_ITEMS.filter((item) => item.roles.some(hasRole));
}

export const NO_STAFF_TOOLS_MESSAGE = 'No staff tools are available for your role yet.';
