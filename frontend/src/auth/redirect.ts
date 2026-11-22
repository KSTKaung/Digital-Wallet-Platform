// Open-redirect guard for "return to where you were" after signing in. The target comes from
// router state, which any page (or a crafted history entry) can set, so it is checked before use.

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
  const pathname = from.split(/[?#]/, 1)[0]!.replace(/\/+$/, '') || '/';
  if (AUTH_PAGES.includes(pathname.toLowerCase())) {
    return DEFAULT_AFTER_LOGIN;
  }
  return from;
}
