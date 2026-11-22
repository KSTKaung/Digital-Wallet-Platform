// Wallet wording shared by the dashboard and the deposit and withdrawal pages.

/** Tiers of doc 06 MVP-ADR-18 (Premium is not offered). */
const TIER_LABELS: Readonly<Record<string, string>> = {
  BASIC: 'Basic',
  VERIFIED: 'Verified',
};

export function tierLabel(tier: string | undefined): string {
  if (!tier) {
    return '—';
  }
  return TIER_LABELS[tier] ?? tier;
}

/** True only for an active wallet. UI only: the server still decides (`409 WALLET_FROZEN`). */
export function canMoveMoney(status: string | undefined): boolean {
  return status === 'ACTIVE';
}

export const FROZEN_EXPLANATION =
  'Your wallet is frozen: it cannot move money, so deposits, withdrawals and transfers are not possible. Only our staff can unfreeze it; contact support.';
export const CLOSED_EXPLANATION = 'Your wallet is closed: it cannot move money.';
export const NOT_ACTIVE_EXPLANATION = 'Your wallet is not active: it cannot move money right now.';

/** Why money cannot move for this status, or null when the wallet is active. */
export function notActiveExplanation(status: string | undefined): string | null {
  if (canMoveMoney(status)) {
    return null;
  }
  if (status === 'FROZEN') {
    return FROZEN_EXPLANATION;
  }
  return status === 'CLOSED' ? CLOSED_EXPLANATION : NOT_ACTIVE_EXPLANATION;
}

export const SIMULATED_MONEY_NOTE =
  'Simulated money: this demo moves no real funds and connects to no bank or card.';

/** Movement statuses: `COMPLETED` is the only MVP value (doc 02 §2.3); others are shown as sent. */
export function movementStatusLabel(status: string | undefined): string {
  if (status === 'COMPLETED') {
    return 'Completed';
  }
  return status ?? 'Unknown';
}

/** Size of `client_reference` (doc 02 §2.3: at most 100 characters); the server checks it. */
export const NOTE_MAX_LENGTH = 100;

export const NOTE_MESSAGES = {
  noLetterOrDigit: 'Note must contain at least one letter or digit.',
  controlCharacters: 'Note cannot contain control or formatting characters.',
  tooLong: `Note can have at most ${NOTE_MAX_LENGTH} characters.`,
} as const;

/**
 * Format checks for the optional deposit note, as doc 02 §2.3 documents them (no control or
 * format characters, at least one letter or digit, at most 100 characters). The server decides.
 */
export function noteError(note: string): string | undefined {
  if (note === '') {
    return undefined;
  }
  if (/[\p{Cc}\p{Cf}]/u.test(note)) {
    return NOTE_MESSAGES.controlCharacters;
  }
  if (!/[\p{L}\p{N}]/u.test(note)) {
    return NOTE_MESSAGES.noLetterOrDigit;
  }
  return note.length > NOTE_MAX_LENGTH ? NOTE_MESSAGES.tooLong : undefined;
}
