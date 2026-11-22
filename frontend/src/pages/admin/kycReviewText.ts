// Staff wording for the KYC review screens (doc 02 §2.7; doc 06 MVP-ADR-10). The customer screens
// have their own labels (pages/kyc/kycText.ts); these speak to the reviewer.

import { KYC_REJECTION_REASON_MAX_LENGTH, type KycReviewStatus } from '../../api/admin';
import type { ChipTone } from '../../components/StatusChip';
import { staffReasonError } from './staffReason';

export const KYC_REVIEW_STATUS_OPTIONS: readonly { value: KycReviewStatus; label: string }[] = [
  { value: 'PENDING_REVIEW', label: 'Pending review' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
];

const STATUS_TONES: Readonly<Record<string, ChipTone>> = {
  PENDING_REVIEW: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
};

/** Unknown values are shown as sent, never guessed. */
export function reviewStatusLabel(status: string | undefined): string {
  return KYC_REVIEW_STATUS_OPTIONS.find((o) => o.value === status)?.label ?? status ?? '—';
}

export function reviewStatusTone(status: string | undefined): ChipTone {
  return (status !== undefined ? STATUS_TONES[status] : undefined) ?? 'neutral';
}

/** The empty queue, per status filter. */
export const EMPTY_QUEUE_MESSAGES: Readonly<Record<KycReviewStatus, string>> = {
  PENDING_REVIEW: 'No applications are waiting for review.',
  APPROVED: 'No approved applications.',
  REJECTED: 'No rejected applications.',
};

/** One message for a missing id and a draft: both are the same `404` (doc 02 §2.7). */
export const KYC_APPLICATION_NOT_FOUND_MESSAGE = 'We could not find this KYC application.';

export const APPROVE_WARNING =
  "Approving verifies the customer and creates the customer's wallet straight away. This cannot be undone.";
export const APPROVED_MESSAGE = "Application approved. The customer's wallet has been created.";

export const REJECT_REASON_HINT =
  'The customer sees this reason, so do not include identity details such as the document reference, name or date of birth.';
export const REJECT_REASON_FORMAT_HINT = `Write it on one line, at most ${KYC_REJECTION_REASON_MAX_LENGTH} characters.`;
export const REJECT_WARNING =
  'Rejecting ends this application. The customer sees the reason below and may apply again.';
export const REJECTED_MESSAGE =
  'Application rejected. The customer can see the reason and may apply again.';

/**
 * The format checks of RejectKycRequest (staffReasonError). The document-reference check is the
 * server's (it answers `400` naming `reason`).
 */
export function rejectReasonError(reason: string): string | undefined {
  return staffReasonError(reason, KYC_REJECTION_REASON_MAX_LENGTH);
}
