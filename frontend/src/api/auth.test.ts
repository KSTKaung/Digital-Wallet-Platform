import { describe, expect, it, vi } from 'vitest';
import {
  getAccessToken,
  getSessionEndReason,
  getSessionSnapshot,
  onSessionEnded,
  setSession,
} from '../auth/tokenStore';
import {
  emptyResponse,
  errorResponse,
  jsonResponse,
  makeJwt,
  stubFetch,
  tokenResponse,
} from '../test/http';
import {
  changePassword,
  forgotPassword,
  login,
  logout,
  register,
  resendOtp,
  resetPassword,
  SessionEndedError,
  verifyOtp,
} from './auth';

const TOKEN = makeJwt({ sub: 'u-1', roles: ['CUSTOMER'], sid: 's-1' });
const NEW = makeJwt({ sub: 'u-1', roles: ['CUSTOMER'], sid: 's-2' });

function bodyOf(body: string | undefined) {
  return body === undefined ? undefined : (JSON.parse(body) as unknown);
}

describe('public auth endpoints', () => {
  it('register posts the documented body without a bearer', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/auth/register': () =>
        jsonResponse(201, { email: 'a@example.com', status: 'PENDING_VERIFICATION' }),
    });
    const request = {
      email: 'a@example.com',
      phoneNumber: '+66812345678',
      password: 'x'.repeat(12),
    };

    await expect(register(request)).resolves.toEqual({
      email: 'a@example.com',
      status: 'PENDING_VERIFICATION',
    });
    const [call] = http.calls;
    expect(bodyOf(call?.body)).toEqual(request);
    expect(call?.headers.has('Authorization')).toBe(false);
  });

  it('verifyOtp REGISTRATION sends the password too and accepts the empty 200', async () => {
    const http = stubFetch({ 'POST /api/v1/auth/verify-otp': () => emptyResponse(200) });

    await expect(
      verifyOtp({
        purpose: 'REGISTRATION',
        identifier: 'a@example.com',
        otp: '123456',
        password: 'p'.repeat(12),
      }),
    ).resolves.toBeUndefined();
    expect(bodyOf(http.calls[0]?.body)).toEqual({
      purpose: 'REGISTRATION',
      identifier: 'a@example.com',
      otp: '123456',
      password: 'p'.repeat(12),
    });
  });

  it('verifyOtp PASSWORD_RESET returns the reset token', async () => {
    stubFetch({ 'POST /api/v1/auth/verify-otp': () => jsonResponse(200, { resetToken: 'rt' }) });

    await expect(
      verifyOtp({ purpose: 'PASSWORD_RESET', identifier: 'a@example.com', otp: '123456' }),
    ).resolves.toEqual({ resetToken: 'rt' });
  });

  it('a wrong OTP surfaces 400 INVALID_OTP without any refresh', async () => {
    const http = stubFetch({
      'POST /api/v1/auth/verify-otp': () => errorResponse(400, 'INVALID_OTP'),
    });

    await expect(
      verifyOtp({ purpose: 'PASSWORD_RESET', identifier: 'a@example.com', otp: '000000' }),
    ).rejects.toMatchObject({ status: 400, errorCode: 'INVALID_OTP' });
    expect(http.calls).toHaveLength(1);
  });

  it('resendOtp, forgotPassword and resetPassword accept their empty 202/200 answers', async () => {
    const http = stubFetch({
      'POST /api/v1/auth/resend-otp': () => emptyResponse(202),
      'POST /api/v1/auth/forgot-password': () => emptyResponse(202),
      'POST /api/v1/auth/reset-password': () => emptyResponse(200),
    });

    await resendOtp({ identifier: 'a@example.com', purpose: 'REGISTRATION' });
    await forgotPassword({ email: 'a@example.com' });
    await resetPassword({ resetToken: 'rt', newPassword: 'n'.repeat(12) });

    expect(http.calls.map((c) => bodyOf(c.body))).toEqual([
      { identifier: 'a@example.com', purpose: 'REGISTRATION' },
      { email: 'a@example.com' },
      { resetToken: 'rt', newPassword: 'n'.repeat(12) },
    ]);
  });

  it('resendOtp exposes the per-IP 429 wait time', async () => {
    stubFetch({
      'POST /api/v1/auth/resend-otp': () =>
        jsonResponse(
          429,
          { success: false, errorCode: 'RATE_LIMITED', message: 'Too many requests' },
          { 'Retry-After': '40' },
        ),
    });

    await expect(
      resendOtp({ identifier: 'a@example.com', purpose: 'PASSWORD_RESET' }),
    ).rejects.toMatchObject({ status: 429, retryAfterSeconds: 40 });
  });
});

describe('login', () => {
  it('stores the access token in memory and never touches Web Storage or cookies', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const getItem = vi.spyOn(Storage.prototype, 'getItem');
    const cookieSet = vi.spyOn(Document.prototype, 'cookie', 'set');
    const http = stubFetch({ 'POST /api/v1/auth/login': () => tokenResponse(TOKEN) });

    const session = await login({ email: 'a@example.com', password: 'p'.repeat(12) });

    expect(session.user).toEqual({ sub: 'u-1', roles: ['CUSTOMER'] });
    expect(getAccessToken()).toBe(TOKEN);
    expect(setItem).not.toHaveBeenCalled();
    expect(getItem).not.toHaveBeenCalled();
    expect(cookieSet).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(window.location.href).not.toContain(TOKEN);
    expect(http.calls[0]?.headers.has('Authorization')).toBe(false);
  });

  it('a 401 (wrong password, unknown or locked account) is thrown without a refresh', async () => {
    const http = stubFetch({
      'POST /api/v1/auth/login': () => errorResponse(401, 'UNAUTHORIZED', 'Invalid credentials'),
    });

    await expect(login({ email: 'a@example.com', password: 'wrong' })).rejects.toMatchObject({
      status: 401,
    });
    expect(http.calls).toHaveLength(1);
    expect(getSessionSnapshot()).toBeNull();
  });

  it('an unreadable token response is an error and leaves no session', async () => {
    stubFetch({
      'POST /api/v1/auth/login': [() => tokenResponse('not-a-jwt'), () => jsonResponse(200, {})],
    });

    await expect(login({ email: 'a@example.com', password: 'p' })).rejects.toMatchObject({
      errorCode: 'SYSTEM_ERROR',
    });
    await expect(login({ email: 'a@example.com', password: 'p' })).rejects.toMatchObject({
      errorCode: 'SYSTEM_ERROR',
    });
    expect(getAccessToken()).toBeNull();
  });
});

describe('logout', () => {
  it('posts logout with the bearer and X-Requested-With, then clears local state', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({ 'POST /api/v1/auth/logout': () => emptyResponse(204) });

    await logout();

    const [call] = http.calls;
    expect(call?.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
    expect(call?.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(0);
    expect(getAccessToken()).toBeNull();
  });

  it('with an expired access token refreshes first, then logs out with the new token', async () => {
    vi.useFakeTimers({ now: 0, toFake: ['Date'] });
    setSession(TOKEN, 900);
    vi.setSystemTime(901_000);
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
      'POST /api/v1/auth/logout': () => emptyResponse(204),
    });

    await logout();

    expect(http.calls.map((c) => c.path)).toEqual(['/api/v1/auth/refresh', '/api/v1/auth/logout']);
    expect(http.calls[1]?.headers.get('Authorization')).toBe(`Bearer ${NEW}`);
    expect(getAccessToken()).toBeNull();
  });

  it('with no token at all refreshes first (a reload) and logs out', async () => {
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
      'POST /api/v1/auth/logout': () => emptyResponse(204),
    });

    await logout();

    expect(http.calls.map((c) => c.path)).toEqual(['/api/v1/auth/refresh', '/api/v1/auth/logout']);
  });

  it('skips the logout call when the refresh says the session is already gone', async () => {
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });

    await logout();

    expect(http.callsTo('POST', '/api/v1/auth/logout')).toHaveLength(0);
    expect(getAccessToken()).toBeNull();
  });

  it('a logout 401 is followed by one refresh and one more logout, so the cookie is cleared', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/auth/logout': [
        () => errorResponse(401, 'UNAUTHORIZED'),
        () => emptyResponse(204),
      ],
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
    });

    await logout();

    expect(http.calls.map((c) => c.path)).toEqual([
      '/api/v1/auth/logout',
      '/api/v1/auth/refresh',
      '/api/v1/auth/logout',
    ]);
    expect(getAccessToken()).toBeNull();
  });

  it('clears local state even when the network fails, and never throws', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'POST /api/v1/auth/logout': () => {
        throw new TypeError('Failed to fetch');
      },
    });

    await expect(logout()).resolves.toBeUndefined();
    expect(getAccessToken()).toBeNull();
  });

  it('clears local state when the server fails with 500', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/auth/logout': () => errorResponse(500, 'SYSTEM_ERROR'),
    });

    await logout();

    expect(http.calls).toHaveLength(1);
    expect(getAccessToken()).toBeNull();
  });

  it('does not emit the session-ended event (a chosen sign-out)', async () => {
    setSession(TOKEN, 900);
    const ended = vi.fn();
    onSessionEnded(ended);
    stubFetch({ 'POST /api/v1/auth/logout': () => emptyResponse(204) });

    await logout();

    expect(ended).not.toHaveBeenCalled();
    expect(getSessionEndReason()).toBe('loggedOut');
  });
});

describe('changePassword', () => {
  const request = { currentPassword: 'c'.repeat(12), newPassword: 'n'.repeat(12) };

  it('on success sends the bearer and ends the local session as passwordChanged', async () => {
    setSession(TOKEN, 900);
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({ 'POST /api/v1/auth/change-password': () => emptyResponse(200) });

    await changePassword(request);

    expect(http.calls[0]?.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
    expect(bodyOf(http.calls[0]?.body)).toEqual(request);
    expect(getAccessToken()).toBeNull();
    expect(ended).toHaveBeenCalledExactlyOnceWith('passwordChanged');
    expect(getSessionEndReason()).toBe('passwordChanged');
  });

  it('a 401 means a wrong current password: no refresh, session kept', async () => {
    setSession(TOKEN, 900);
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({
      'POST /api/v1/auth/change-password': () => errorResponse(401, 'UNAUTHORIZED'),
    });

    await expect(changePassword(request)).rejects.toMatchObject({ status: 401 });

    expect(http.calls).toHaveLength(1);
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(0);
    expect(getAccessToken()).toBe(TOKEN);
    expect(ended).not.toHaveBeenCalled();
  });

  it('a 400 (policy or reuse) keeps the session', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'POST /api/v1/auth/change-password': () => errorResponse(400, 'VALIDATION_ERROR'),
    });

    await expect(changePassword(request)).rejects.toMatchObject({ status: 400 });
    expect(getAccessToken()).toBe(TOKEN);
  });

  it('refreshes an expired token before the call, so its 401 stays unambiguous', async () => {
    vi.useFakeTimers({ now: 0, toFake: ['Date'] });
    setSession(TOKEN, 900);
    vi.setSystemTime(901_000);
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => tokenResponse(NEW),
      'POST /api/v1/auth/change-password': () => emptyResponse(200),
    });

    await changePassword(request);

    expect(http.calls.map((c) => c.path)).toEqual([
      '/api/v1/auth/refresh',
      '/api/v1/auth/change-password',
    ]);
    expect(http.calls[1]?.headers.get('Authorization')).toBe(`Bearer ${NEW}`);
  });

  it('ends the session without sending the request when the refresh is refused', async () => {
    const ended = vi.fn();
    onSessionEnded(ended);
    const http = stubFetch({
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });

    await expect(changePassword(request)).rejects.toBeInstanceOf(SessionEndedError);
    expect(http.callsTo('POST', '/api/v1/auth/change-password')).toHaveLength(0);
    expect(ended).toHaveBeenCalledExactlyOnceWith('sessionEnded');
  });
});
