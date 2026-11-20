// Transfer wording and the browser's format checks (doc 02 §2.3 "MVP wallet lookup", §2.4).
// Only formats are checked here: the check digit, eligibility, limits and balance are the
// server's (D8; MVP-ADR-20 X2: a wrong check digit is counted like a missing wallet, so it is
// never checked in the browser).

import { formatMoney } from '../../format/money';

/** Exactly 11 ASCII digits (doc 02 §2.3 validation step 1). */
const WALLET_NUMBER_FORMAT = /^[0-9]{11}$/;

export const WALLET_NUMBER_MESSAGES = {
  empty: 'Enter the wallet number.',
  format: 'A wallet number has exactly 11 digits, with no spaces or other characters.',
} as const;

/**
 * The wallet number to look up, or the error to show. Spaces around a pasted number are dropped;
 * anything else must be exactly 11 ASCII digits.
 */
export function checkWalletNumber(
  raw: string,
): { ok: true; walletNumber: string } | { ok: false; error: string } {
  const value = raw.trim();
  if (value === '') {
    return { ok: false, error: WALLET_NUMBER_MESSAGES.empty };
  }
  return WALLET_NUMBER_FORMAT.test(value)
    ? { ok: true, walletNumber: value }
    : { ok: false, error: WALLET_NUMBER_MESSAGES.format };
}

/** Description size in Unicode code points, as PostgreSQL `varchar(140)` counts (doc 02 §2.4). */
export const DESCRIPTION_MAX_LENGTH = 140;

export const DESCRIPTION_MESSAGES = {
  controlCharacters:
    'Description cannot contain control or formatting characters, such as line breaks, tabs or invisible characters.',
  tooLong: `Description can have at most ${DESCRIPTION_MAX_LENGTH} characters.`,
} as const;

/** Code points, not UTF-16 units: a flag emoji is 2, a lone surrogate 1. */
export function codePointLength(text: string): number {
  return Array.from(text).length;
}

/**
 * Checks the optional description as typed, never trimmed: no `Cc`, `Cs` or `Cf` character and at
 * most 140 code points. An empty description is valid (it is left out of the request).
 */
export function descriptionError(description: string): string | undefined {
  if (description === '') {
    return undefined;
  }
  if (/[\p{Cc}\p{Cs}\p{Cf}]/u.test(description)) {
    return DESCRIPTION_MESSAGES.controlCharacters;
  }
  return codePointLength(description) > DESCRIPTION_MAX_LENGTH
    ? DESCRIPTION_MESSAGES.tooLong
    : undefined;
}

/** One message for every `404` of the lookup or the transfer: it never says why (MVP-ADR-19). */
export const RECIPIENT_NOT_FOUND_MESSAGE =
  'No wallet can receive money at this number. Check the number and try again.';

export const LOOKUP_RATE_LIMITED_MESSAGE = 'Too many wallet number checks.';

export const TRANSFER_NOT_FOUND_MESSAGE = 'We could not find this transfer.';

export const HELD_FOR_REVIEW_NOTE =
  'The amount is held from your available balance while we review the transfer, for at most 24 hours. The recipient receives nothing until it is approved. If it is not decided in time, it expires and the held amount returns to your available balance.';

/** Customer statuses of doc 02 §2.4. */
const STATUS_LABELS: Readonly<Record<string, string>> = {
  COMPLETED: 'Completed',
  PENDING_REVIEW: 'Held for review',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  BLOCKED: 'Refused',
};

export function transferStatusLabel(status: string | undefined): string {
  if (!status) {
    return 'Unknown';
  }
  return STATUS_LABELS[status] ?? status;
}

/** The customer-facing rejection categories (MVP-ADR-24 Q10); never the reviewer's reason. */
const REJECTION_LABELS: Readonly<Record<string, string>> = {
  REVIEW_DECLINED: 'Declined after review.',
  WALLET_UNAVAILABLE: 'Not completed because a wallet could not take part in the transfer.',
};

export function rejectionReasonText(reason: string | null | undefined): string | null {
  if (!reason) {
    return null;
  }
  return REJECTION_LABELS[reason] ?? reason;
}

/** "Sent" / "Received"; anything else as sent. */
export function directionLabel(direction: string | undefined): string {
  if (direction === 'SENT') {
    return 'Sent';
  }
  if (direction === 'RECEIVED') {
    return 'Received';
  }
  return direction ?? 'Unknown';
}

/** "To" for a sent transfer, "From" for a received one. */
export function counterpartyPrefix(direction: string | undefined): string {
  if (direction === 'SENT') {
    return 'To';
  }
  return direction === 'RECEIVED' ? 'From' : 'Other party';
}

/** "−1,500.00 THB" for money sent, "+1,500.00 THB" for money received (never by colour alone). */
export function signedTransferAmount(transfer: {
  amount?: number;
  currency?: string;
  direction?: string;
}): string {
  const money = formatMoney(transfer.amount, transfer.currency);
  if (transfer.direction === 'SENT') {
    return `−${money}`;
  }
  return transfer.direction === 'RECEIVED' ? `+${money}` : money;
}

/** "Somchai J. (*******8901)", or "—" when the server sent neither. */
export function counterpartyText(
  counterparty: { maskedName?: string; maskedWalletNumber?: string } | null | undefined,
): string {
  const name = counterparty?.maskedName;
  const number = counterparty?.maskedWalletNumber;
  if (name && number) {
    return `${name} (${number})`;
  }
  return name ?? number ?? '—';
}
