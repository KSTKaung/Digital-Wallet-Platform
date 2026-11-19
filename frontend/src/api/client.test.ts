import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, getAccessToken, onSessionEnded, setSession } from '../auth/tokenStore';
import {
  errorResponse,
  installFakeLocks,
  jsonResponse,
  makeJwt,
  stubFetch,
  tokenResponse,
  type RecordedRequest,
} from '../test/http';
import { ApiError, apiRequest } from './client';

describe('apiRequest', () => {
  it('calls the Gateway base URL from VITE_API_BASE_URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { status: 'UP' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/actuator/health')).resolves.toEqual({ status: 'UP' });
    expect(fetchMock).toHaveBeenCalledWith(
      'http://gateway.test/actuator/health',
      expect.anything(),
    );
  });

  it('uses same-origin relative URLs when VITE_API_BASE_URL is empty', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { status: 'UP' }));
    vi.stubGlobal('fetch', fetchMock);

    await apiRequest('/actuator/health');
    expect(fetchMock).toHaveBeenCalledWith('/actuator/health', expect.anything());
  });

  it('maps the documented error body to ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(409, {
          success: false,
          errorCode: 'INSUFFICIENT_BALANCE',
          message: 'Available balance is lower than the requested amount',
          correlationId: 'c-1',
        }),
      ),
    );

    const error = await apiRequest('/api/v1/transfers').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 409,
      errorCode: 'INSUFFICIENT_BALANCE',
      correlationId: 'c-1',
    });
  });

  it('falls back to SYSTEM_ERROR and the header correlation id for non-JSON errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('Bad Gateway', { status: 502, headers: { 'X-Correlation-Id': 'c-2' } }),
        ),
    );

    await expect(apiRequest('/api/v1/wallet')).rejects.toMatchObject({
      status: 502,
      errorCode: 'SYSTEM_ERROR',
      correlationId: 'c-2',
    });
  });

  it('exposes Retry-After on 503 SERVICE_DEGRADED', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            503,
            { success: false, errorCode: 'SERVICE_DEGRADED', message: 'Try later' },
            { 'Retry-After': '30' },
          ),
        ),
    );

    await expect(apiRequest('/api/v1/transfers')).rejects.toMatchObject({
      errorCode: 'SERVICE_DEGRADED',
      retryAfterSeconds: 30,
    });
  });

  it('refuses internal paths without calling fetch', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/internal/v1/wallet/movements')).rejects.toThrow(/internal path/);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const OLD = makeJwt({ sub: 'u-1', roles: ['CUSTOMER'], sid: 'old' });
const NEW = makeJwt({ sub: 'u-1', roles: ['CUSTOMER'], sid: 'new' });
const KEY = '7f1c2a9e-1b2c-4d3e-8f40-123456789abc';

function bearerOf(request: RecordedRequest | undefined) {
  return request?.headers.get('Authorization');
}

describe('apiRequest session handling', () => {
  let restoreLocks: () => void = () => undefined;

  beforeEach(() => {
    setSession(OLD, 900);
  });

  afterEach(() => {
    restoreLocks();
    restoreLocks = () => undefined;
  });

  it('sends the bearer token, a UUID correlation id and same-origin credentials', async () => {
    const http = stubFetch({ 'GET /api/v1/wallet': () => jsonResponse(200, { id: 'w' }) });

    await apiRequest('/api/v1/wallet');

    const [call] = http.calls;
    expect(bearerOf(call)).toBe(`Bearer ${OLD}`);
    expect(call?.headers.get('X-Correlation-Id')).toMatch(UUID);
    expect(call?.headers.get('Accept')).toBe('application/json');
    expect(call?.headers.has('Idempotency-Key')).toBe(false);
    expect(call?.credentials).toBe('same-origin');
  });

  it('uses a new correlation id per request and keeps a caller-supplied one', async () => {
    const http = stubFetch({ 'GET /api/v1/wallet': () => jsonResponse(200, {}) });

    await apiRequest('/api/v1/wallet');
    await apiRequest('/api/v1/wallet');
    await apiRequest('/api/v1/wallet', { headers: { 'X-Correlation-Id': 'mine' } });

    const ids = http.calls.map((c) => c.headers.get('X-Correlation-Id'));
    expect(ids[0]).not.toBe(ids[1]);
    expect(ids[2]).toBe('mine');
  });

  it('does not send the bearer to public auth endpoints or non-API paths', async () => {
    const http = stubFetch({
      'POST /api/v1/auth/login': () => tokenResponse(NEW),
      'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }),
    });

    await apiRequest('/api/v1/auth/login', { method: 'POST', body: '{}' });
    await apiRequest('/actuator/health');

    expect(http.calls.every((c) => !c.headers.has('Authorization'))).toBe(true);
  });

  it('returns undefined for 204 and for an empty 200 body', async () => {
    stubFetch({
      'POST /api/v1/a': () => new Response(null, { status: 204 }),
      'POST /api/v1/b': () => new Response('', { status: 200 }),
    });

    await expect(apiRequest('/api/v1/a', { method: 'POST' })).resolves.toBeUndefined();
    await expect(apiRequest('/api/v1/b', { method: 'POST' })).resolves.toBeUndefined();
  });

  it('three concurrent 401s cause exactly one refresh, then each request is retried', async () => {
    const locks = installFakeLocks();
    restoreLocks = locks.restore;
    const http = stubFetch({
      'GET /api/v1/wallet': (r) =>
        bearerOf(r) === `Bearer ${NEW}`
          ? jsonResponse(200, { ok: true })
          : errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
    });

    const results = await Promise.all([
      apiRequest('/api/v1/wallet'),
      apiRequest('/api/v1/wallet'),
      apiRequest('/api/v1/wallet'),
    ]);

    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }]);
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(1);
    expect(locks.requests).toEqual([{ name: 'wallet-refresh', mode: 'exclusive' }]);
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(6);
  });

  it('a 401 for a token that was already replaced retries with the new token, no refresh', async () => {
    const http = stubFetch({
      'GET /api/v1/wallet': (r) => {
        // The token changes while this request is in flight (another caller refreshed).
        if (bearerOf(r) === `Bearer ${OLD}`) {
          setSession(NEW, 900);
          return errorResponse(401, 'UNAUTHORIZED');
        }
        return jsonResponse(200, { ok: true });
      },
    });

    await expect(apiRequest('/api/v1/wallet')).resolves.toEqual({ ok: true });
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(0);
    expect(bearerOf(http.calls[1])).toBe(`Bearer ${NEW}`);
  });

  it('retries a money POST with the new bearer and the same key, body and correlation id', async () => {
    const http = stubFetch({
      'POST /api/v1/wallet/deposits': [
        () => errorResponse(401, 'UNAUTHORIZED'),
        () => jsonResponse(201, { status: 'COMPLETED', ledgerStatus: 'POSTED' }),
      ],
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
    });
    const body = JSON.stringify({ amount: 100.5, currency: 'THB' });

    await expect(
      apiRequest('/api/v1/wallet/deposits', { method: 'POST', body, idempotencyKey: KEY }),
    ).resolves.toEqual({ status: 'COMPLETED', ledgerStatus: 'POSTED' });

    const [first, retry] = http.callsTo('POST', '/api/v1/wallet/deposits');
    expect(bearerOf(first)).toBe(`Bearer ${OLD}`);
    expect(bearerOf(retry)).toBe(`Bearer ${NEW}`);
    for (const call of [first, retry]) {
      expect(call?.headers.get('Idempotency-Key')).toBe(KEY);
      expect(call?.headers.get('Content-Type')).toBe('application/json');
      expect(call?.body).toBe(body);
    }
    expect(retry?.headers.get('X-Correlation-Id')).toBe(first?.headers.get('X-Correlation-Id'));
  });

  it('a second 401 after the retry ends the session and throws the ApiError', async () => {
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({
      'GET /api/v1/wallet': () => errorResponse(401, 'UNAUTHORIZED', 'Authentication required'),
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
    });

    const error = await apiRequest('/api/v1/wallet').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 401 });
    expect(getAccessToken()).toBeNull();
    expect(ended).toHaveBeenCalledTimes(1);
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2);
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(1);
  });

  it('a refused refresh ends the session without a retry', async () => {
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({
      'GET /api/v1/wallet': () => errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });

    await expect(apiRequest('/api/v1/wallet')).rejects.toMatchObject({ status: 401 });

    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(1);
    expect(getAccessToken()).toBeNull();
    expect(ended).toHaveBeenCalledTimes(1);
  });

  it('a tab that never had a session clears silently (no session-ended event)', async () => {
    clearSession();
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({
      'GET /api/v1/wallet': () => errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });

    await expect(apiRequest('/api/v1/wallet')).rejects.toMatchObject({ status: 401 });
    expect(http.calls[0]?.headers.has('Authorization')).toBe(false);
    expect(ended).not.toHaveBeenCalled();
  });

  it('a failing refresh (network) propagates and keeps the session', async () => {
    stubFetch({
      'GET /api/v1/wallet': () => errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/refresh': () => {
        throw new TypeError('Failed to fetch');
      },
    });

    await expect(apiRequest('/api/v1/wallet')).rejects.toThrow('Failed to fetch');
    expect(getAccessToken()).toBe(OLD);
  });

  it.each([
    '/api/v1/auth/register',
    '/api/v1/auth/verify-otp',
    '/api/v1/auth/resend-otp',
    '/api/v1/auth/forgot-password',
    '/api/v1/auth/reset-password',
    '/api/v1/auth/login',
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
    '/api/v1/auth/change-password',
  ])('never refreshes on a 401 from %s, even when asked', async (path) => {
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({ [`POST ${path}`]: () => errorResponse(401, 'UNAUTHORIZED') });

    await expect(
      apiRequest(path, { method: 'POST', body: '{}', retryOn401: true }),
    ).rejects.toMatchObject({ status: 401 });

    expect(http.calls).toHaveLength(1);
    expect(getAccessToken()).toBe(OLD);
    expect(ended).not.toHaveBeenCalled();
  });

  it('retryOn401: false surfaces the 401 without refreshing', async () => {
    const http = stubFetch({ 'GET /api/v1/wallet': () => errorResponse(401, 'UNAUTHORIZED') });

    await expect(apiRequest('/api/v1/wallet', { retryOn401: false })).rejects.toMatchObject({
      status: 401,
    });
    expect(http.calls).toHaveLength(1);
  });

  it('ignores the query string when classifying paths', async () => {
    const http = stubFetch({ 'POST /api/v1/auth/login': () => errorResponse(401, 'UNAUTHORIZED') });

    await expect(
      apiRequest('/api/v1/auth/login?x=1', { method: 'POST', body: '{}' }),
    ).rejects.toMatchObject({ status: 401 });
    expect(http.calls).toHaveLength(1);
    expect(http.calls[0]?.headers.has('Authorization')).toBe(false);
  });

  it('passes other errors through after a successful retry', async () => {
    stubFetch({
      'POST /api/v1/wallet/withdrawals': [
        () => errorResponse(401, 'UNAUTHORIZED'),
        () => errorResponse(409, 'INSUFFICIENT_BALANCE'),
      ],
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
    });

    await expect(
      apiRequest('/api/v1/wallet/withdrawals', { method: 'POST', body: '{}' }),
    ).rejects.toMatchObject({ status: 409, errorCode: 'INSUFFICIENT_BALANCE' });
    expect(getAccessToken()).toBe(NEW);
  });
});
