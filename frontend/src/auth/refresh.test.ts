import { afterEach, describe, expect, it } from 'vitest';
import {
  deferred,
  errorResponse,
  installFakeLocks,
  jsonResponse,
  makeJwt,
  removeLocks,
  stubFetch,
  tokenResponse,
} from '../test/http';
import { REFRESH_LOCK_NAME, refreshAccessToken } from './refresh';
import { getAccessToken, getSessionSnapshot, setSession } from './tokenStore';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const cleanups: (() => void)[] = [];

afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

describe('refreshAccessToken', () => {
  it('runs under the exclusive wallet-refresh Web Lock and stores the new token', async () => {
    const locks = installFakeLocks();
    cleanups.push(locks.restore);
    const token = makeJwt({ sub: 'u-1', roles: ['CUSTOMER'] });
    const http = stubFetch({ 'POST /api/v1/auth/refresh': () => tokenResponse(token) });

    await expect(refreshAccessToken()).resolves.toBe('refreshed');

    expect(locks.requests).toEqual([{ name: REFRESH_LOCK_NAME, mode: 'exclusive' }]);
    expect(REFRESH_LOCK_NAME).toBe('wallet-refresh');
    expect(getAccessToken()).toBe(token);
    const [call] = http.calls;
    expect(call?.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(call?.headers.get('X-Correlation-Id')).toMatch(UUID);
    expect(call?.headers.has('Authorization')).toBe(false);
    expect(call?.credentials).toBe('same-origin');
    expect(call?.body).toBeUndefined();
  });

  it('shares one in-flight refresh between concurrent callers in a tab', async () => {
    const locks = installFakeLocks();
    cleanups.push(locks.restore);
    const gate = deferred<Response>();
    const http = stubFetch({ 'POST /api/v1/auth/refresh': () => gate.promise });

    const results = Promise.all([refreshAccessToken(), refreshAccessToken(), refreshAccessToken()]);
    gate.resolve(tokenResponse(makeJwt({ sub: 'u-1' })));

    await expect(results).resolves.toEqual(['refreshed', 'refreshed', 'refreshed']);
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(1);
    expect(locks.requests).toHaveLength(1);
  });

  it('serialises refreshes behind the lock (another tab holds it)', async () => {
    const locks = installFakeLocks();
    cleanups.push(locks.restore);
    const otherTab = deferred<void>();
    // Another tab takes the lock first and holds it.
    void navigator.locks.request(REFRESH_LOCK_NAME, { mode: 'exclusive' }, () => otherTab.promise);
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => tokenResponse(makeJwt({ sub: 'u' })),
    });

    const pending = refreshAccessToken();
    await Promise.resolve();
    await Promise.resolve();
    expect(http.calls).toHaveLength(0);

    otherTab.resolve();
    await expect(pending).resolves.toBe('refreshed');
    expect(http.calls).toHaveLength(1);
  });

  it('falls back to in-tab single-flight when navigator.locks is missing', async () => {
    cleanups.push(removeLocks());
    const gate = deferred<Response>();
    const http = stubFetch({ 'POST /api/v1/auth/refresh': () => gate.promise });

    const results = Promise.all([refreshAccessToken(), refreshAccessToken()]);
    gate.resolve(tokenResponse(makeJwt({ sub: 'u-1' })));

    await expect(results).resolves.toEqual(['refreshed', 'refreshed']);
    expect(http.calls).toHaveLength(1);
  });

  it('starts a new refresh after the previous one settled', async () => {
    cleanups.push(removeLocks());
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => tokenResponse(makeJwt({ sub: 'u-1' })),
    });

    await refreshAccessToken();
    await refreshAccessToken();

    expect(http.calls).toHaveLength(2);
  });

  it.each([401, 403])('resolves signedOut and clears the session on %i', async (status) => {
    setSession(makeJwt({ sub: 'u-1' }), 900);
    stubFetch({ 'POST /api/v1/auth/refresh': () => errorResponse(status, 'UNAUTHORIZED') });

    await expect(refreshAccessToken()).resolves.toBe('signedOut');
    expect(getAccessToken()).toBeNull();
  });

  it('resolves signedOut when the 200 body has no usable token', async () => {
    stubFetch({
      'POST /api/v1/auth/refresh': [
        () => jsonResponse(200, { expiresIn: 900 }),
        () => new Response('not json', { status: 200 }),
        () => tokenResponse('malformed'),
      ],
    });

    await expect(refreshAccessToken()).resolves.toBe('signedOut');
    await expect(refreshAccessToken()).resolves.toBe('signedOut');
    await expect(refreshAccessToken()).resolves.toBe('signedOut');
    expect(getSessionSnapshot()).toBeNull();
  });

  it('rejects on 429 with Retry-After and keeps the current session', async () => {
    const token = makeJwt({ sub: 'u-1' });
    setSession(token, 900);
    stubFetch({
      'POST /api/v1/auth/refresh': () =>
        jsonResponse(
          429,
          { success: false, errorCode: 'RATE_LIMITED', message: 'Slow down' },
          { 'Retry-After': '12' },
        ),
    });

    await expect(refreshAccessToken()).rejects.toMatchObject({
      status: 429,
      errorCode: 'RATE_LIMITED',
      retryAfterSeconds: 12,
    });
    expect(getAccessToken()).toBe(token);
  });

  it('rejects on a network error and allows a later attempt', async () => {
    const fetchCalls = { n: 0 };
    stubFetch({
      'POST /api/v1/auth/refresh': () => {
        fetchCalls.n += 1;
        if (fetchCalls.n === 1) throw new TypeError('Failed to fetch');
        return tokenResponse(makeJwt({ sub: 'u-1' }));
      },
    });

    await expect(refreshAccessToken()).rejects.toThrow('Failed to fetch');
    await expect(refreshAccessToken()).resolves.toBe('refreshed');
  });
});
