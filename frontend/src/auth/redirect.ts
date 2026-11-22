// Open-redirect guard for "return to where you were" after signing in. The target comes from
// router state, which any page (or a crafted history entry) can set, so it is checked before use.

import { isStaff } from './roles';
import type { Role } from './tokenStore';

export const DEFAULT_AFTER_LOGIN = '/';

const AUTH_PAGES = ['/login', '/register', '/verify', '/forgot-password', '/reset-password'];

/**
 * Returns `from` when it is a same-app path, else `/`. Accepted: starts with a single `/`, has no
 * backslash, no control or whitespace characters and no scheme-like `//`, and is not one of the
 * signed-out pages (going back to /login after signing in would loop).
 */
export function safeRedirectPath(from: unknown): string {
  if (typeof from !== 'string' || from.length === 0 || from.length > 2048) {
    return DEFAULT_AFTER_LOGIN;
  }
  if (!from.startsWith('/') || from.startsWith('//')) {
    return DEFAULT_AFTER_LOGIN;
  }
  // Browsers treat "\" like "/" ("/\evil.example"), and control characters can be stripped
  // before parsing, so any of them is refused outright.
  // eslint-disable-next-line no-control-regex
  if (/[\\\s\u0000-\u001f\u007f]/.test(from)) {
    return DEFAULT_AFTER_LOGIN;
  }
  if (AUTH_PAGES.includes(pathnameOf(from))) {
    return DEFAULT_AFTER_LOGIN;
  }
  return from;
}

/** The staff landing page (doc 05 §7.1: staff screens are under /admin). */
export const STAFF_HOME = '/admin';

/** Pages both kinds of account use. */
const SHARED_PAGES = ['/settings/password'];

/**
 * Where to go after signing in: `from` when it is safe (safeRedirectPath) and meant for this kind
 * of account. Staff land on /admin unless `from` is a staff or shared page; customers never return
 * to an /admin page. Matching is case-insensitive, as the router's is. UI only: the server decides.
 */
export function afterLoginPath(from: unknown, roles: readonly Role[]): string {
  const safe = safeRedirectPath(from);
  const pathname = pathnameOf(safe);
  const staffPage = pathname === STAFF_HOME || pathname.startsWith(`${STAFF_HOME}/`);
  if (isStaff(roles)) {
    return staffPage || SHARED_PAGES.includes(pathname) ? safe : STAFF_HOME;
  }
  return staffPage ? DEFAULT_AFTER_LOGIN : safe;
}

/** "/Admin/KYC/?x=1" → "/admin/kyc" */
function pathnameOf(path: string): string {
  return (path.split(/[?#]/, 1)[0]!.replace(/\/+$/, '') || '/').toLowerCase();
}
