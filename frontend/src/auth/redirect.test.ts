import { describe, expect, it } from 'vitest';
import { afterLoginPath, safeRedirectPath } from './redirect';
import { isStaff } from './roles';
import type { Role } from './tokenStore';

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

describe('afterLoginPath', () => {
  const STAFF: Role[][] = [['COMPLIANCE_OFFICER'], ['ADMIN'], ['ADMIN', 'COMPLIANCE_OFFICER']];

  it.each(STAFF)('sends %s staff to /admin by default', (...roles) => {
    expect(afterLoginPath(undefined, roles)).toBe('/admin');
    expect(afterLoginPath('/', roles)).toBe('/admin');
  });

  it.each([
    ['/admin/kyc', '/admin/kyc'],
    ['/admin/kyc/abc?x=1', '/admin/kyc/abc?x=1'],
    ['/admin', '/admin'],
    ['/ADMIN/KYC', '/ADMIN/KYC'],
    ['/settings/password', '/settings/password'],
    ['/transfers', '/admin'],
    ['/deposit', '/admin'],
    ['/administrator', '/admin'],
    ['//evil.example', '/admin'],
    ['/login', '/admin'],
  ])('a staff "from" of %s goes to %s', (from, expected) => {
    expect(afterLoginPath(from, ['COMPLIANCE_OFFICER'])).toBe(expected);
  });

  it.each([
    ['/transfers?page=2', '/transfers?page=2'],
    ['/settings/password', '/settings/password'],
    ['/admin', '/'],
    ['/admin/kyc', '/'],
    ['/Admin/Kyc/', '/'],
    ['//evil.example', '/'],
    [undefined, '/'],
  ])('a customer "from" of %s goes to %s', (from, expected) => {
    expect(afterLoginPath(from, ['CUSTOMER'])).toBe(expected);
  });

  it('treats an account without a known role like a customer', () => {
    expect(afterLoginPath('/admin', [])).toBe('/');
    expect(afterLoginPath('/transfers', [])).toBe('/transfers');
  });
});

describe('isStaff', () => {
  it('is true for a staff role without the customer role', () => {
    expect(isStaff(['COMPLIANCE_OFFICER'])).toBe(true);
    expect(isStaff(['ADMIN'])).toBe(true);
    expect(isStaff(['CUSTOMER'])).toBe(false);
    expect(isStaff(['CUSTOMER', 'ADMIN'])).toBe(false);
    expect(isStaff([])).toBe(false);
  });
});
