// Idempotency-Key lifecycle for money confirm steps (doc 02 §1.4, §7 item 3; documentations/08
// §4D.6).
//
// The page holds one key bound to the draft it was created for (exactly what the request sends:
// deposit amount + note, withdrawal amount, transfer receiver wallet number + amount +
// description). The key is chosen when a confirm step opens: the held key when the draft is
// exactly the same as before, otherwise a new one. It is reused for retries and double clicks,
// and also after Edit / Change recipient when the customer comes back with the same draft, so an
// unknown outcome (network error, 5xx) followed by a second confirm is replayed by the server,
// never executed twice. Renew it after a terminal answer: a success, a stored refusal (the server
// replays it for the same key) or a key bound to another request; the next attempt, even with
// the same draft, then gets a new key. Errors that leave no record (validation, KYC_REQUIRED,
// 429) and unknown outcomes keep the key. The key lives in page state only: leaving the page
// forgets it.

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

/**
 * True when the outcome of a money POST is unknown: no API answer (network error, a body that is
 * not the documented JSON) or a 5xx. The movement may have happened; the key is kept, and the
 * customer is told to check their history before trying again.
 */
export function isUnknownOutcome(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status >= 500;
}

/** A draft's identity for key reuse: the exact values the request sends, in a fixed order. */
export function draftFingerprint(...values: readonly (string | undefined)[]): string {
  return JSON.stringify(values.map((value) => value ?? null));
}

export interface DraftKeyHandle {
  /** The key for the draft last opened (stable across re-renders), or null before the first. */
  key: string | null;
  /**
   * Call when a confirm step opens for a draft (see draftFingerprint). Keeps the held key when
   * the draft is exactly the one it was created for, otherwise creates a new key.
   */
  open: (fingerprint: string) => void;
  /** After a terminal answer: replaces the key, so the next attempt never reuses it. */
  renew: () => void;
}

export function useDraftIdempotencyKey(): DraftKeyHandle {
  const [held, setHeld] = useState<{ fingerprint: string; key: string } | null>(null);
  const open = useCallback((fingerprint: string) => {
    setHeld((prev) =>
      prev?.fingerprint === fingerprint ? prev : { fingerprint, key: newIdempotencyKey() },
    );
  }, []);
  const renew = useCallback(() => {
    setHeld((prev) => (prev === null ? null : { ...prev, key: newIdempotencyKey() }));
  }, []);
  return { key: held?.key ?? null, open, renew };
}
