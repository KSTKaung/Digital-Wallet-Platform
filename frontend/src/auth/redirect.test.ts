import { describe, expect, it } from 'vitest';
import { safeRedirectPath } from './redirect';

describe('safeRedirectPath (open-redirect guard)', () => {
  it.each(['/', '/transfers', '/transfers/123', '/settings/password', '/transactions?page=2'])(
    'accepts the in-app path %s',
    (path) => {
      expect(safeRedirectPath(path)).toBe(path);
    },
  );

  it.each([
    ['protocol-relative', '//evil.example'],
    ['protocol-relative with path', '//evil.example/login'],
    ['backslash', '/\\evil.example'],
    ['absolute URL', 'https://evil.example/'],
    ['javascript scheme', 'javascript:alert(1)'],
    ['relative path', 'transfers'],
    ['empty', ''],
    ['tab', '/\t/evil.example'],
    ['newline', '/\n/evil.example'],
    ['space', '/ /evil.example'],
    ['login', '/login'],
    ['login with slash', '/login/'],
    ['login with query', '/login?x=1'],
    ['login in capitals', '/LOGIN'],
    ['register', '/register'],
    ['verify', '/verify'],
    ['forgot-password', '/forgot-password'],
    ['reset-password', '/reset-password'],
    ['too long', `/${'a'.repeat(2048)}`],
  ])('refuses %s', (_name, path) => {
    expect(safeRedirectPath(path)).toBe('/');
  });

  it.each([undefined, null, 42, { pathname: '/x' }])('refuses the non-string %s', (value) => {
    expect(safeRedirectPath(value)).toBe('/');
  });
});
