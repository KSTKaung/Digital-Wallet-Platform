import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import {
  draftFingerprint,
  isKeySpentError,
  isUnknownOutcome,
  newIdempotencyKey,
  STORED_REFUSAL_CODES,
  useDraftIdempotencyKey,
} from './idempotency';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function apiError(errorCode: string, status = 409) {
  return new ApiError(status, errorCode, 'msg');
}

describe('useDraftIdempotencyKey', () => {
  const A = draftFingerprint('100.00', 'note');
  const B = draftFingerprint('100.01', 'note');

  it('has no key until a confirm step opens, then a UUID v4 stable across re-renders', () => {
    const { result, rerender } = renderHook(() => useDraftIdempotencyKey());
    expect(result.current.key).toBeNull();

    act(() => result.current.open(A));
    const first = result.current.key;

    expect(first).toMatch(UUID_V4);
    rerender();
    expect(result.current.key).toBe(first);
  });

  it('opening the same draft again keeps the key; a different draft gets a new one', () => {
    const { result } = renderHook(() => useDraftIdempotencyKey());
    act(() => result.current.open(A));
    const first = result.current.key;

    act(() => result.current.open(A));
    expect(result.current.key).toBe(first);

    act(() => result.current.open(B));
    expect(result.current.key).toMatch(UUID_V4);
    expect(result.current.key).not.toBe(first);
  });

  it('renew() replaces the key, also for the same draft', () => {
    const { result } = renderHook(() => useDraftIdempotencyKey());
    act(() => result.current.open(A));
    const first = result.current.key;

    act(() => result.current.renew());
    const renewed = result.current.key;
    act(() => result.current.open(A));

    expect(renewed).toMatch(UUID_V4);
    expect(renewed).not.toBe(first);
    expect(result.current.key).toBe(renewed);
  });

  it('renew() before any draft keeps no key', () => {
    const { result } = renderHook(() => useDraftIdempotencyKey());
    act(() => result.current.renew());
    expect(result.current.key).toBeNull();
  });

  it('each page (mount) gets its own key', () => {
    const a = renderHook(() => useDraftIdempotencyKey());
    const b = renderHook(() => useDraftIdempotencyKey());
    act(() => a.result.current.open(A));
    act(() => b.result.current.open(A));
    expect(a.result.current.key).not.toBe(b.result.current.key);
  });

  it('newIdempotencyKey returns distinct UUID v4 values', () => {
    const keys = new Set(Array.from({ length: 20 }, newIdempotencyKey));
    expect(keys.size).toBe(20);
    for (const key of keys) expect(key).toMatch(UUID_V4);
  });
});

describe('draftFingerprint', () => {
  it('tells apart values that would join to the same text, and undefined from empty', () => {
    expect(draftFingerprint('1', '23')).not.toBe(draftFingerprint('12', '3'));
    expect(draftFingerprint('1', undefined)).not.toBe(draftFingerprint('1', ''));
    expect(draftFingerprint('100.00')).not.toBe(draftFingerprint('100'));
    expect(draftFingerprint('a', 'b')).toBe(draftFingerprint('a', 'b'));
  });
});

describe('isUnknownOutcome', () => {
  it.each([
    ['a network error', new TypeError('Failed to fetch')],
    ['500', apiError('SYSTEM_ERROR', 500)],
    ['a non-JSON 504', apiError('SYSTEM_ERROR', 504)],
    ['503', apiError('SERVICE_DEGRADED', 503)],
  ])('%s is unknown', (_name, error) => {
    expect(isUnknownOutcome(error)).toBe(true);
  });

  it.each([
    ['429', apiError('RATE_LIMITED', 429)],
    ['400', apiError('VALIDATION_ERROR', 400)],
    ['409', apiError('INSUFFICIENT_BALANCE', 409)],
  ])('%s is a known answer', (_name, error) => {
    expect(isUnknownOutcome(error)).toBe(false);
  });
});

describe('isKeySpentError', () => {
  it('lists exactly the stored refusals of doc 02 §1.4', () => {
    expect([...STORED_REFUSAL_CODES].sort()).toEqual([
      'DAILY_LIMIT_EXCEEDED',
      'INSUFFICIENT_BALANCE',
      'SINGLE_LIMIT_EXCEEDED',
      'WALLET_FROZEN',
    ]);
  });

  it.each([
    'INSUFFICIENT_BALANCE',
    'DAILY_LIMIT_EXCEEDED',
    'SINGLE_LIMIT_EXCEEDED',
    'WALLET_FROZEN',
    'RISK_BLOCKED',
    'IDEMPOTENCY_KEY_REUSED',
  ])('%s spends the key', (code) => {
    expect(isKeySpentError(apiError(code))).toBe(true);
  });

  it.each([
    ['KYC_REQUIRED', 403],
    ['VALIDATION_ERROR', 400],
    ['INVALID_AMOUNT', 400],
    ['RATE_LIMITED', 429],
    ['SYSTEM_ERROR', 500],
    ['SERVICE_DEGRADED', 503],
  ])('%s keeps the key (nothing stored)', (code, status) => {
    expect(isKeySpentError(apiError(code, status))).toBe(false);
  });

  it('a network error keeps the key', () => {
    expect(isKeySpentError(new TypeError('Failed to fetch'))).toBe(false);
  });
});
