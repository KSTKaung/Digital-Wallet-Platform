import { describe, expect, it, vi } from 'vitest';
import { makeJwt } from '../test/http';
import {
  clearSession,
  decodeJwtClaims,
  endSession,
  getAccessToken,
  getSessionSnapshot,
  hasFreshAccessToken,
  onSessionEnded,
  setSession,
  subscribe,
} from './tokenStore';

describe('decodeJwtClaims', () => {
  it('reads sub and the known roles from the payload', () => {
    const token = makeJwt({ sub: 'u-1', roles: ['CUSTOMER', 'ADMIN', 'ROOT', 7], sid: 's' });
    expect(decodeJwtClaims(token)).toEqual({ sub: 'u-1', roles: ['CUSTOMER', 'ADMIN'] });
  });

  it('decodes base64url payloads with UTF-8 and without padding', () => {
    const token = makeJwt({ sub: 'ผู้ใช้-?>', roles: ['COMPLIANCE_OFFICER'] });
    expect(decodeJwtClaims(token)).toEqual({ sub: 'ผู้ใช้-?>', roles: ['COMPLIANCE_OFFICER'] });
  });

  it('treats a missing roles claim as no roles', () => {
    expect(decodeJwtClaims(makeJwt({ sub: 'u-1' }))).toEqual({ sub: 'u-1', roles: [] });
  });

  it.each([
    ['empty string', ''],
    ['two parts', 'a.b'],
    ['four parts', 'a.b.c.d'],
    ['empty payload', 'a..c'],
    ['bad base64', 'a.!!!.c'],
    ['not JSON', `a.${btoa('not json')}.c`],
    ['JSON null', `a.${btoa('null')}.c`],
    ['no sub', makeJwt({ roles: ['CUSTOMER'] })],
    ['empty sub', makeJwt({ sub: '' })],
    ['numeric sub', makeJwt({ sub: 42 })],
  ])('returns null for a malformed token (%s) without throwing', (_label, token) => {
    expect(decodeJwtClaims(token)).toBeNull();
  });
});

describe('token store', () => {
  it('keeps the token in memory with an expiry and notifies subscribers', () => {
    vi.useFakeTimers({ now: new Date('2026-11-19T00:00:00Z') });
    const listener = vi.fn();
    const unsubscribe = subscribe(listener);
    const token = makeJwt({ sub: 'u-1', roles: ['CUSTOMER'] });

    const snapshot = setSession(token, 900);

    expect(getAccessToken()).toBe(token);
    expect(snapshot).toEqual({
      user: { sub: 'u-1', roles: ['CUSTOMER'] },
      expiresAt: Date.parse('2026-11-19T00:15:00Z'),
    });
    expect(getSessionSnapshot()).toBe(snapshot);
    expect(snapshot).not.toHaveProperty('accessToken');
    expect(listener).toHaveBeenCalledTimes(1);

    unsubscribe();
    clearSession();
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('reports freshness with a skew before expiry', () => {
    vi.useFakeTimers({ now: 0 });
    setSession(makeJwt({ sub: 'u-1' }), 900);
    expect(hasFreshAccessToken()).toBe(true);

    vi.setSystemTime(900_000 - 30_000);
    expect(hasFreshAccessToken()).toBe(false);
    expect(hasFreshAccessToken(0)).toBe(true);
  });

  it('treats a missing or invalid expiresIn as already expired', () => {
    setSession(makeJwt({ sub: 'u-1' }), Number.NaN);
    expect(getAccessToken()).not.toBeNull();
    expect(hasFreshAccessToken(0)).toBe(false);
  });

  it('a malformed token clears an existing session instead of storing it', () => {
    setSession(makeJwt({ sub: 'u-1' }), 900);

    expect(setSession('garbage', 900)).toBeNull();

    expect(getAccessToken()).toBeNull();
    expect(getSessionSnapshot()).toBeNull();
    expect(hasFreshAccessToken()).toBe(false);
  });

  it('clearSession notifies only when something changed', () => {
    const listener = vi.fn();
    subscribe(listener);
    clearSession();
    expect(listener).not.toHaveBeenCalled();
  });

  it('endSession clears and emits the session-ended event', () => {
    const ended = vi.fn();
    const off = onSessionEnded(ended);
    setSession(makeJwt({ sub: 'u-1' }), 900);

    endSession();

    expect(getAccessToken()).toBeNull();
    expect(ended).toHaveBeenCalledTimes(1);
    off();
    endSession();
    expect(ended).toHaveBeenCalledTimes(1);
  });
});
