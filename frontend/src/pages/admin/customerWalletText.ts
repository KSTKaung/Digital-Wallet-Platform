// Staff wording for the wallet freeze screen (doc 02 §2.7 "Wallet freeze (MVP, role ADMIN)"; doc
// 06 MVP-ADR-10 amendment, MVP-ADR-25). The response is only `{status}` and a repeat is also
// `200`, so no text claims that this request changed the wallet.

import { WALLET_STATUS_REASON_MAX_LENGTH } from '../../api/admin';
import { staffReasonError } from './staffReason';

export type WalletStatusAction = 'freeze' | 'unfreeze';

/** The status each action leads to (WalletStatusService.Change). */
export const TARGET_STATUS: Readonly<Record<WalletStatusAction, string>> = {
  freeze: 'FROZEN',
  unfreeze: 'ACTIVE',
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The customer id as it is sent: surrounding spaces removed, lower case. `undefined` when the text
 * is not a UUID (8-4-4-4-12 hexadecimal digits, either case), so it is never sent.
 */
export function parseCustomerId(value: string): string | undefined {
  const trimmed = value.trim();
  return UUID.test(trimmed) ? trimmed.toLowerCase() : undefined;
}

export function customerIdError(value: string): string | undefined {
  if (value.trim() === '') {
    return 'Enter the customer ID.';
  }
  if (parseCustomerId(value) === undefined) {
    return 'Customer ID must be a UUID, such as 0a1b2c3d-4e5f-4a6b-8c7d-8e9f00112233.';
  }
  return undefined;
}

/** The format checks of WalletStatusReasonRequest, which are those of RejectKycRequest. */
export function walletStatusReasonError(reason: string): string | undefined {
  return staffReasonError(reason, WALLET_STATUS_REASON_MAX_LENGTH);
}

export const CUSTOMER_WALLET_INTRO =
  "Freeze or unfreeze a customer's wallet by the customer's ID. Every change is audited with your staff ID and reason.";
export const NO_STATUS_READ_NOTE =
  "This screen cannot show the wallet's current status. Each action reports the status after it.";

export const CUSTOMER_ID_HINT =
  'The customer ID is a UUID: 32 hexadecimal digits in groups of 8-4-4-4-12.';
export const WALLET_STATUS_REASON_HINT =
  'Staff only: the reason is kept in the audit log. The email to the customer never includes it.';
export const WALLET_STATUS_REASON_FORMAT_HINT = `Write it on one line, at most ${WALLET_STATUS_REASON_MAX_LENGTH} characters.`;

/** Unknown customer and customer without a wallet: one identical `404`, so one text. */
export const CUSTOMER_WALLET_NOT_FOUND_MESSAGE =
  'We could not find a wallet for this customer ID. Check the ID and try again.';

export const CONFIRM_HEADINGS: Readonly<Record<WalletStatusAction, string>> = {
  freeze: 'Freeze this wallet?',
  unfreeze: 'Unfreeze this wallet?',
};

export const CONFIRM_WARNINGS: Readonly<Record<WalletStatusAction, string>> = {
  freeze:
    'Freezing stops all money movement: the customer can no longer deposit, withdraw or send money, and no money can reach the wallet, until an admin unfreezes it. The customer is emailed that the wallet was frozen; the email does not include your reason.',
  unfreeze:
    'Unfreezing makes the wallet active again, so money can move in and out of it. The customer is emailed that the wallet was unfrozen; the email does not include your reason.',
};

export const CONFIRM_REPEAT_NOTES: Readonly<Record<WalletStatusAction, string>> = {
  freeze: 'If the wallet is already frozen, nothing changes and no email is sent.',
  unfreeze: 'If the wallet is already active, nothing changes and no email is sent.',
};

export const CONFIRM_BUTTONS: Readonly<Record<WalletStatusAction, string>> = {
  freeze: 'Yes, freeze the wallet',
  unfreeze: 'Yes, unfreeze the wallet',
};

export const BUSY_BUTTONS: Readonly<Record<WalletStatusAction, string>> = {
  freeze: 'Freezing…',
  unfreeze: 'Unfreezing…',
};

/**
 * The result of a `200`. When the status is the action's target, the text says what the status is,
 * never that this request changed it: a repeat gives the same answer. Any other status (not
 * reachable in the MVP) is reported as sent.
 */
export function resultMessage(action: WalletStatusAction, status: string | undefined): string {
  if (status === TARGET_STATUS[action]) {
    return action === 'freeze'
      ? 'Freeze completed. The wallet is frozen (status FROZEN). If it was already frozen, nothing changed and no email was sent.'
      : 'Unfreeze completed. The wallet is active (status ACTIVE). If it was already active, nothing changed and no email was sent.';
  }
  return `The server reports the wallet status as ${status ?? 'unknown'}, not ${TARGET_STATUS[action]}.`;
}
