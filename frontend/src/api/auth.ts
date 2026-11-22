// Auth endpoints (doc 02 §2.1, doc 06 MVP-ADR-14 and MVP-ADR-23). Request and response types come
// from the generated schema by path and method; `purpose` is narrowed to the values doc 02 allows.

import { refreshAccessToken } from '../auth/refresh';
import {
  clearSession,
  endSession,
  hasFreshAccessToken,
  setSession,
  type SessionSnapshot,
} from '../auth/tokenStore';
import { ApiError, apiRequest } from './client';
import type { RequestBodyOf, ResponseBodyOf } from './types';

export type RegisterRequest = RequestBodyOf<'/api/v1/auth/register', 'post'>;
export type RegisterResponse = ResponseBodyOf<'/api/v1/auth/register', 'post'>;
export type LoginRequest = RequestBodyOf<'/api/v1/auth/login', 'post'>;
export type TokenResponse = ResponseBodyOf<'/api/v1/auth/login', 'post'>;
export type ForgotPasswordRequest = RequestBodyOf<'/api/v1/auth/forgot-password', 'post'>;
export type ResetPasswordRequest = RequestBodyOf<'/api/v1/auth/reset-password', 'post'>;
export type ChangePasswordRequest = RequestBodyOf<'/api/v1/auth/change-password', 'post'>;
export type ResetTokenResponse = ResponseBodyOf<'/api/v1/auth/verify-otp', 'post'>;

type VerifyOtpBody = RequestBodyOf<'/api/v1/auth/verify-otp', 'post'>;
type ResendOtpBody = RequestBodyOf<'/api/v1/auth/resend-otp', 'post'>;

/** `LOGIN` is refused by the server (MFA is Backlog, MVP-ADR-23). */
export type OtpPurpose = 'REGISTRATION' | 'PASSWORD_RESET';

/** REGISTRATION needs the password too, so a code alone never activates an account. */
export type RegistrationOtpRequest = Omit<VerifyOtpBody, 'purpose' | 'password'> & {
  purpose: 'REGISTRATION';
  password: string;
};
export type PasswordResetOtpRequest = Omit<VerifyOtpBody, 'purpose' | 'password'> & {
  purpose: 'PASSWORD_RESET';
};
export type ResendOtpRequest = Omit<ResendOtpBody, 'purpose'> & { purpose: OtpPurpose };

const AUTH = '/api/v1/auth';
const CSRF_HEADER = { 'X-Requested-With': 'XMLHttpRequest' } as const;

function post<T>(path: string, body?: unknown, init: { headers?: HeadersInit } = {}): Promise<T> {
  return apiRequest<T>(`${AUTH}${path}`, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
    ...init,
  });
}

/** 201 with the same body for a new and an existing account (MVP-ADR-23). */
export function register(request: RegisterRequest): Promise<RegisterResponse> {
  return post<RegisterResponse>('/register', request);
}

/** REGISTRATION: empty 200. PASSWORD_RESET: `{resetToken}`. Every failure is `400 INVALID_OTP`. */
export function verifyOtp(request: RegistrationOtpRequest): Promise<void>;
export function verifyOtp(request: PasswordResetOtpRequest): Promise<ResetTokenResponse>;
export function verifyOtp(
  request: RegistrationOtpRequest | PasswordResetOtpRequest,
): Promise<ResetTokenResponse | void> {
  return post<ResetTokenResponse | void>('/verify-otp', request);
}

/** Always 202 (a cooldown or cap silently sends nothing); only the per-IP limit gives 429. */
export function resendOtp(request: ResendOtpRequest): Promise<void> {
  return post<void>('/resend-otp', request);
}

/** Always 202. */
export function forgotPassword(request: ForgotPasswordRequest): Promise<void> {
  return post<void>('/forgot-password', request);
}

export function resetPassword(request: ResetPasswordRequest): Promise<void> {
  return post<void>('/reset-password', request);
}

/**
 * Signs in and keeps the access token in memory; the refresh token arrives as an HttpOnly
 * cookie. A wrong password, an unknown account and a locked account give the same 401.
 */
export async function login(request: LoginRequest): Promise<SessionSnapshot> {
  const tokens = await post<TokenResponse | undefined>('/login', request);
  const session =
    tokens && typeof tokens.accessToken === 'string'
      ? setSession(tokens.accessToken, Number(tokens.expiresIn))
      : null;
  if (!session) {
    clearSession();
    throw new ApiError(500, 'SYSTEM_ERROR', 'The sign-in response could not be read');
  }
  return session;
}

/**
 * Ends the session on the server, then always clears local state, even if the server call fails.
 * Logout with an expired access token returns 401 and keeps the refresh cookie (MVP-ADR-23), so
 * a missing or expiring token is refreshed first, and a 401 from logout is followed by one
 * refresh and one more logout. Never throws.
 *
 * Resolves true when the server confirmed it: logout answered 2xx, or a refresh said the session
 * was already gone (401/403). Resolves false for anything else (network error, 5xx, 429, a
 * logout that failed again after the refresh): the HttpOnly refresh cookie may still be valid,
 * so the next page load could restore the session. The end reason (`loggedOut` or
 * `logoutUnconfirmed`) is recorded either way, so /login shows the matching notice.
 */
export async function logout(): Promise<boolean> {
  let confirmed = false;
  try {
    confirmed = await endServerSession();
  } catch {
    // Network or server failure: unconfirmed; local state is cleared below regardless.
  } finally {
    clearSession(confirmed ? 'loggedOut' : 'logoutUnconfirmed');
  }
  return confirmed;
}

async function endServerSession(): Promise<boolean> {
  if (!hasFreshAccessToken() && (await refreshAccessToken()) === 'signedOut') {
    return true;
  }
  try {
    await postLogout();
    return true;
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) {
      return false;
    }
  }
  if ((await refreshAccessToken()) === 'signedOut') {
    return true;
  }
  await postLogout();
  return true;
}

function postLogout(): Promise<void> {
  return post<void>('/logout', undefined, { headers: CSRF_HEADER });
}

/** Thrown when an authenticated auth call finds the session already ended (no request sent). */
export class SessionEndedError extends Error {
  constructor() {
    super('Your session has ended');
    this.name = 'SessionEndedError';
  }
}

/**
 * Changes the password. A 401 here means a wrong current password, so it never triggers a
 * refresh (the client's opt-out list). To keep that 401 unambiguous, an expired access token is
 * refreshed before the call. On success the server ends every session and clears the cookie,
 * so the local session ends with the reason `passwordChanged`: the app goes to /login with the
 * "Password changed" notice from the session layer, not from the page.
 */
export async function changePassword(request: ChangePasswordRequest): Promise<void> {
  if (!hasFreshAccessToken() && (await refreshAccessToken()) === 'signedOut') {
    endSession();
    throw new SessionEndedError();
  }
  await post<void>('/change-password', request);
  endSession('passwordChanged');
}
