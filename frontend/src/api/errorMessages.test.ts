import { describe, expect, it } from 'vitest';
import {
  describeError,
  GENERIC_ERROR_MESSAGE,
  isKycRequired,
  validationErrorField,
  LOGIN_FAILED_MESSAGE,
  NETWORK_ERROR_MESSAGE,
  waitText,
} from './errorMessages';
import { ApiError } from './http';

const err = (status: number, code: string, message = 'server text', retry?: number) =>
  new ApiError(status, code, message, 'corr-1', retry);

describe('describeError', () => {
  it.each([
    ['INVALID_OTP', /code is wrong or has expired/],
    ['OTP_EXPIRED', /code has expired/],
    ['OTP_ATTEMPTS_EXCEEDED', /Too many wrong codes/],
    ['KYC_REQUIRED', /^Complete KYC to continue\.$/],
    ['WALLET_NOT_FOUND', /^Complete KYC to continue\.$/],
    ['INSUFFICIENT_BALANCE', /^Insufficient balance/],
    ['DAILY_LIMIT_EXCEEDED', /^Daily limit exceeded/],
    ['WALLET_FROZEN', /^Wallet frozen/],
    ['SYSTEM_ERROR', /on our side/],
    ['FORBIDDEN', /not available for your account/],
    ['NOT_FOUND', /could not find/],
    ['SINGLE_LIMIT_EXCEEDED', /above the limit for a single transaction/],
    ['INVALID_AMOUNT', /minimum amount and use at most 2 decimal places/],
    ['CURRENCY_MISMATCH', /Only Thai baht \(THB\)/],
    ['IDEMPOTENCY_KEY_REUSED', /no longer matches your request/],
    ['SELF_TRANSFER', /^You cannot send money to your own wallet.$/],
    ['RISK_BLOCKED', /^The transfer was refused.$/],
    ['INVALID_TRANSFER_STATE', /can no longer be cancelled/],
  ])('maps %s to customer text', (code, expected) => {
    const described = describeError(err(400, code));
    expect(described.message).toMatch(expected);
    expect(described.message).not.toContain('server text');
    expect(described.correlationId).toBe('corr-1');
  });

  it('never reveals a lock: ACCOUNT_LOCKED reads like any failed sign-in', () => {
    expect(describeError(err(403, 'ACCOUNT_LOCKED')).message).toBe(LOGIN_FAILED_MESSAGE);
  });

  it('uses a generic text for unknown codes and never echoes the server message', () => {
    const described = describeError(err(409, 'SOMETHING_NEW', 'internal detail'));
    expect(described.message).toBe(GENERIC_ERROR_MESSAGE);
  });

  it('shows the server message for VALIDATION_ERROR with a readable field label', () => {
    expect(
      describeError(err(400, 'VALIDATION_ERROR', 'newPassword: must have 12 to 128 characters'))
        .message,
    ).toBe('New password must have 12 to 128 characters.');
    expect(
      describeError(err(400, 'VALIDATION_ERROR', 'phoneNumber: must match "\\+[0-9]{8,15}"'))
        .message,
    ).toBe('Mobile number is not in the expected format.');
    expect(describeError(err(400, 'VALIDATION_ERROR', 'someField: is bad.')).message).toBe(
      'someField is bad.',
    );
    expect(describeError(err(400, 'VALIDATION_ERROR', 'Malformed request body')).message).toBe(
      'Malformed request body',
    );
    expect(describeError(err(400, 'VALIDATION_ERROR', '  ')).message).toBe(
      'Please check the form and try again.',
    );
  });

  it('labels the money request fields', () => {
    expect(describeError(err(400, 'VALIDATION_ERROR', 'amount: must not be null')).message).toBe(
      'Amount must not be null.',
    );
    expect(
      describeError(err(400, 'VALIDATION_ERROR', 'reference: must contain a letter or digit'))
        .message,
    ).toBe('Note must contain a letter or digit.');
    expect(describeError(err(400, 'VALIDATION_ERROR', 'currency: must not be null')).message).toBe(
      'Currency must not be null.',
    );
  });

  it('labels the transfer request fields', () => {
    expect(
      describeError(err(400, 'VALIDATION_ERROR', 'receiverWalletNumber: must be 11 digits'))
        .message,
    ).toBe('Wallet number must be 11 digits.');
    expect(
      describeError(err(400, 'VALIDATION_ERROR', 'walletNumber: parameter is required')).message,
    ).toBe('Wallet number parameter is required.');
    expect(
      describeError(
        err(400, 'VALIDATION_ERROR', 'description: must not contain control or format characters'),
      ).message,
    ).toBe('Description must not contain control or format characters.');
  });

  it('adds the Retry-After wait to 429 and 503 messages', () => {
    expect(describeError(err(429, 'RATE_LIMITED', 'x', 30)).message).toBe(
      'Too many requests. Please wait 30 seconds and try again.',
    );
    expect(describeError(err(429, 'VERIFICATION_ATTEMPTS_EXCEEDED', 'x', 1)).message).toBe(
      'Too many attempts. Please wait 1 second and try again.',
    );
    expect(describeError(err(503, 'SERVICE_DEGRADED', 'x', 12)).message).toBe(
      'Service busy, try again. Please wait 12 seconds and try again.',
    );
    expect(describeError(err(429, 'RATE_LIMITED')).message).toBe(
      'Too many requests. Please try again later.',
    );
  });

  it('treats an unknown code on 429 or 503 as a rate limit or busy service', () => {
    expect(describeError(err(429, 'OTHER', 'x', 5)).message).toBe(
      'Too many requests. Please wait 5 seconds and try again.',
    );
    expect(describeError(err(503, 'OTHER', 'x')).message).toBe(
      'Service busy, try again. Please try again later.',
    );
  });

  it('applies per-screen overrides', () => {
    expect(describeError(err(401, 'UNAUTHORIZED'), { UNAUTHORIZED: 'Custom' }).message).toBe(
      'Custom',
    );
  });

  it('describes a network failure without a correlation id', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toEqual({
      message: NETWORK_ERROR_MESSAGE,
    });
  });

  it('omits the reference when the server sent no correlation id', () => {
    expect(describeError(new ApiError(400, 'INVALID_OTP', 'x'))).not.toHaveProperty(
      'correlationId',
    );
  });
});

describe('waitText', () => {
  it('rounds fractions up and never says zero', () => {
    expect(waitText(0)).toBe('Please wait 1 second and try again.');
    expect(waitText(2.1)).toBe('Please wait 3 seconds and try again.');
  });
});

describe('KYC helpers', () => {
  it('isKycRequired recognises 404 WALLET_NOT_FOUND and 403 KYC_REQUIRED only', () => {
    expect(isKycRequired(err(404, 'WALLET_NOT_FOUND'))).toBe(true);
    expect(isKycRequired(err(403, 'KYC_REQUIRED'))).toBe(true);
    expect(isKycRequired(err(404, 'NOT_FOUND'))).toBe(false);
    expect(isKycRequired(err(403, 'FORBIDDEN'))).toBe(false);
    expect(isKycRequired(err(409, 'WALLET_NOT_FOUND'))).toBe(false);
    expect(isKycRequired(new Error('network'))).toBe(false);
  });

  it('validationErrorField names the field of a VALIDATION_ERROR only', () => {
    const missing = err(400, 'VALIDATION_ERROR', 'dateOfBirth: must not be null');
    expect(validationErrorField(missing)).toBe('dateOfBirth');
    const malformed = err(400, 'VALIDATION_ERROR', 'Malformed request body');
    expect(validationErrorField(malformed)).toBeUndefined();
    expect(validationErrorField(err(409, 'INVALID_KYC_STATE', 'dateOfBirth: x'))).toBeUndefined();
    expect(validationErrorField('dateOfBirth: x')).toBeUndefined();
  });

  it('labels the profile and KYC fields in validation messages', () => {
    const profile = err(
      400,
      'VALIDATION_ERROR',
      'dateOfBirth: must be set in the profile before submitting',
    );
    expect(describeError(profile).message).toBe(
      'Date of birth must be set in the profile before submitting.',
    );
    const size = err(400, 'VALIDATION_ERROR', 'documentReference: size must be between 0 and 50');
    expect(describeError(size).message).toBe('Document reference size must be between 0 and 50.');
  });

  it('has texts for KYC_ALREADY_ACTIVE and INVALID_KYC_STATE that never echo the server', () => {
    expect(describeError(err(409, 'KYC_ALREADY_ACTIVE')).message).toBe(
      'You already have an active KYC application.',
    );
    expect(describeError(err(409, 'INVALID_KYC_STATE')).message).toMatch(/current state/);
  });
});
