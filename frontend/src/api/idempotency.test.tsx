import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import {
  isKeySpentError,
  newIdempotencyKey,
  STORED_REFUSAL_CODES,
  useIdempotencyKey,
} from './idempotency';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function apiError(errorCode: string, status = 409) {
  return new ApiError(status, errorCode, 'msg');
}

describe('useIdempotencyKey', () => {
  it('creates a UUID v4 when the confirm step mounts and keeps it across re-renders', () => {
    const { result, rerender } = renderHook(() => useIdempotencyKey());
    const first = result.current.key;

    expect(first).toMatch(UUID_V4);
    rerender();
    rerender();
    expect(result.current.key).toBe(first);
  });

  it('renew() replaces the key', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const first = result.current.key;

    act(() => result.current.renew());

    expect(result.current.key).toMatch(UUID_V4);
    expect(result.current.key).not.toBe(first);
  });

  it('each confirm step (mount) gets its own key', () => {
    const a = renderHook(() => useIdempotencyKey());
    const b = renderHook(() => useIdempotencyKey());
    expect(a.result.current.key).not.toBe(b.result.current.key);
  });

  it('newIdempotencyKey returns distinct UUID v4 values', () => {
    const keys = new Set(Array.from({ length: 20 }, newIdempotencyKey));
    expect(keys.size).toBe(20);
    for (const key of keys) expect(key).toMatch(UUID_V4);
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
