// One Idempotency-Key per money confirm step (doc 02 §1.4, §7 item 3; documentations/08 §4D.6).
//
// The key is created when the confirm step mounts and is reused for retries and double clicks,
// so the server executes the movement at most once. Renew it after a terminal answer: a success,
// a stored refusal (the server replays it for the same key), a key bound to another request, or
// when the inputs change. Errors that leave no record (validation, KYC_REQUIRED, 429, 5xx,
// network) keep the key, so a retry stays safe.

import { useCallback, useState } from 'react';
import { ApiError } from './client';
import { newUuid } from './http';

/** Refusals stored under the key and replayed for it (doc 02 §1.4). */
export const STORED_REFUSAL_CODES: ReadonlySet<string> = new Set([
  'INSUFFICIENT_BALANCE',
  'DAILY_LIMIT_EXCEEDED',
  'SINGLE_LIMIT_EXCEEDED',
  'WALLET_FROZEN',
]);

/**
 * Answers after which the key is spent: the stored refusals, `RISK_BLOCKED` (a transfer's
 * creation-time answer, replayed for the same key) and `IDEMPOTENCY_KEY_REUSED` (the key belongs
 * to a different request).
 */
const KEY_SPENT_CODES: ReadonlySet<string> = new Set([
  ...STORED_REFUSAL_CODES,
  'RISK_BLOCKED',
  'IDEMPOTENCY_KEY_REUSED',
]);

export function newIdempotencyKey(): string {
  return newUuid();
}

/** True when an error ends this key's life and the next attempt needs a new key. */
export function isKeySpentError(error: unknown): boolean {
  return error instanceof ApiError && KEY_SPENT_CODES.has(error.errorCode);
}

export interface IdempotencyKeyHandle {
  /** The key to send; stable across re-renders until renew() is called. */
  key: string;
  /** Replace the key (after a terminal response or when the inputs change). */
  renew: () => void;
}

export function useIdempotencyKey(): IdempotencyKeyHandle {
  const [key, setKey] = useState(newIdempotencyKey);
  const renew = useCallback(() => setKey(newIdempotencyKey()), []);
  return { key, renew };
}
