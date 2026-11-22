// Staff wording for the transfer review screens (doc 02 §2.7; doc 06 MVP-ADR-24). The values are
// the backend's enums (risk.domain RiskRule, RiskDecision, ReviewAction, DecisionSource,
// RequestedAction, SystemReason, StaffRejectionCategory; transfer.domain RejectionCategory). A
// label is a plain name for the code, never a meaning the docs do not give; an unknown value is
// shown as sent.

import { TRANSFER_REJECTION_REASON_MAX_LENGTH } from '../../api/admin';

function labelFrom(labels: Readonly<Record<string, string>>) {
  return (value: string | null | undefined, fallback = '—'): string => {
    if (value === null || value === undefined || value === '') {
      return fallback;
    }
    return labels[value] ?? value;
  };
}

/** The four MVP rules (RiskRule); the code itself is always shown next to the label. */
export const ruleLabel = labelFrom({
  TRANSFER_AMOUNT: 'Transfer amount',
  TRANSFER_FREQUENCY: 'Transfer frequency',
  NEW_RECIPIENT: 'New recipient',
  NEW_SENDER_WALLET: 'New sender wallet',
});

export const riskDecisionLabel = labelFrom({
  ALLOW: 'Allow',
  MONITOR: 'Monitor',
  MANUAL_REVIEW: 'Manual review',
  BLOCK: 'Block',
});

export const reviewActionLabel = labelFrom({
  APPROVE: 'Approved',
  REJECT: 'Rejected',
  CANCEL: 'Cancelled',
  EXPIRE: 'Expired',
});

export const decidedByLabel = labelFrom({
  STAFF: 'Staff',
  CUSTOMER: 'Customer (sender)',
  SYSTEM: 'System',
});

export const requestedActionLabel = labelFrom({
  APPROVE: 'Approve',
  REJECT: 'Reject',
  CANCEL: 'Cancel',
});

export const reasonCategoryLabel = labelFrom({
  STAFF_REJECTION: 'Staff rejection',
});

export const systemReasonLabel = labelFrom({
  SENDER_WALLET_FROZEN: "Sender's wallet frozen",
  SENDER_WALLET_CLOSED: "Sender's wallet closed",
  RECIPIENT_WALLET_FROZEN: "Recipient's wallet frozen",
  RECIPIENT_WALLET_CLOSED: "Recipient's wallet closed",
  REVIEW_DEADLINE_PASSED: 'Review deadline passed',
});

/** The customer-facing category on the transfer (transfer.domain RejectionCategory). */
export const rejectionCategoryLabel = labelFrom({
  REVIEW_DECLINED: 'Declined after review',
  WALLET_UNAVAILABLE: 'Wallet unavailable',
});

/**
 * Whether the review deadline had passed at `now` (when the data arrived, on this device's clock).
 * Display only: the server decides with the database time, and an overdue transfer stays in the
 * queue until it is expired.
 */
export function isOverdue(reviewExpiresAt: string | null | undefined, now: number): boolean {
  if (!reviewExpiresAt) {
    return false;
  }
  const deadline = Date.parse(reviewExpiresAt);
  return !Number.isNaN(deadline) && deadline <= now;
}

export const OVERDUE_LABEL = 'Overdue';

export const TRANSFER_REVIEW_NOT_FOUND_MESSAGE = 'We could not find this transfer review.';

export const EMPTY_TRANSFER_QUEUE_MESSAGE = 'No transfers are waiting for review.';

export const QUEUE_PAGING_NOTE =
  'Transfers leave this list as they are decided, so decisions taken while you page through it can move transfers to an earlier page. Refresh to see the current list.';

export const QUEUE_OVERDUE_NOTE =
  'An overdue transfer stays listed until it is expired. Approving or rejecting it after its deadline expires it instead, and the held amount returns to the sender.';

export const APPROVE_TRANSFER_WARNING =
  'Approving moves the money to the recipient now. This cannot be undone. If a wallet is frozen or closed, the transfer is rejected instead and the held amount returns to the sender.';
export const APPROVED_TRANSFER_MESSAGE = 'Transfer approved. The latest details are shown below.';

export const REJECT_TRANSFER_REASON_HINT =
  'Staff only: the customer never sees this text, only a generic category ("Declined after review").';
export const REJECT_TRANSFER_REASON_FORMAT_HINT = `Write it on one line, at most ${TRANSFER_REJECTION_REASON_MAX_LENGTH} characters.`;
export const REJECT_TRANSFER_WARNING =
  'Rejecting ends this transfer. The held amount returns to the sender, and the recipient receives nothing.';
export const REJECTED_TRANSFER_MESSAGE =
  'Transfer rejected. The held amount has returned to the sender.';
/** Approve answered `200 REJECTED` (`WALLET_UNAVAILABLE`, MVP-ADR-24 case 3). */
export const APPROVAL_REJECTED_MESSAGE =
  'Not approved: a wallet is frozen or closed, so the transfer was rejected instead and the held amount has returned to the sender. The recipient received nothing.';
/** A `200` with any other status: state only what the answer shows. */
export const DECISION_RECORDED_MESSAGE =
  'The decision was recorded. The transfer status is shown below.';

/**
 * The notice after a `200`, chosen from the status the server returned, never from the action
 * alone: an approval can end `REJECTED` when a wallet is frozen or closed (security and code
 * review, PR 6b step 11).
 */
export function decisionMessage(
  decision: 'approved' | 'rejected',
  status: string | undefined,
): string {
  if (decision === 'approved' && status === 'COMPLETED') {
    return APPROVED_TRANSFER_MESSAGE;
  }
  if (status === 'REJECTED') {
    return decision === 'approved' ? APPROVAL_REJECTED_MESSAGE : REJECTED_TRANSFER_MESSAGE;
  }
  return DECISION_RECORDED_MESSAGE;
}

/**
 * Java's String.strip(): Character.isWhitespace, which is the Unicode space separators except the
 * no-break spaces (U+00A0, U+2007, U+202F), the line and paragraph separators, and U+0009–U+000D,
 * U+001C–U+001F.
 */
const JAVA_WHITESPACE =
  '(?![\\u00A0\\u2007\\u202F])[\\t\\n\\u000B\\f\\r\\u001C-\\u001F\\p{Zs}\\u2028\\u2029]';
const JAVA_STRIP = new RegExp(`^(?:${JAVA_WHITESPACE})+|(?:${JAVA_WHITESPACE})+$`, 'gu');

/**
 * The format checks of TextRules.requireReason, in its order: the text is NFC-normalised and
 * stripped, then it must not be blank, must not contain control, surrogate or format characters
 * (so a line break inside the text is refused), must have at most 500 code points and must contain
 * a letter or a digit. The text is still sent as typed; the server normalises it.
 */
export function rejectTransferReasonError(reason: string): string | undefined {
  const checked = reason.normalize('NFC').replace(JAVA_STRIP, '');
  if (checked === '') {
    return 'Enter a reason.';
  }
  if (/[\r\n]/.test(checked)) {
    return 'Reason must be on one line, without line breaks.';
  }
  if (/[\p{Cc}\p{Cs}\p{Cf}]/u.test(checked)) {
    return 'Reason must not contain control characters or invisible formatting characters.';
  }
  if (Array.from(checked).length > TRANSFER_REJECTION_REASON_MAX_LENGTH) {
    return `Reason must have at most ${TRANSFER_REJECTION_REASON_MAX_LENGTH} characters.`;
  }
  if (!/[\p{L}\p{N}]/u.test(checked)) {
    return 'Reason must contain a letter or a digit.';
  }
  return undefined;
}
