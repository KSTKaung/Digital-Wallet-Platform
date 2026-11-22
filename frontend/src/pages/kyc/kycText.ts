// Display labels for KYC values (doc 02 §2.2). Unknown values are shown as sent, never guessed.

import type { DocumentType, VerificationLevel } from '../../api/kyc';
import type { SelectOption } from '../../components/SelectField';

export const LEVEL_OPTIONS: readonly SelectOption<VerificationLevel>[] = [
  { value: 'BASIC', label: 'Basic' },
  { value: 'FULL', label: 'Full' },
];

export const DOCUMENT_OPTIONS: readonly SelectOption<DocumentType>[] = [
  { value: 'ID_CARD', label: 'ID card' },
  { value: 'PASSPORT', label: 'Passport' },
];

export const STATUS_LABELS: Readonly<Record<string, string>> = {
  NONE: 'Not started',
  DRAFT: 'Draft (not submitted)',
  PENDING_REVIEW: 'Verification under review',
  APPROVED: 'Approved',
  REJECTED: 'Not approved',
};

export function optionLabel(
  options: readonly { value: string; label: string }[],
  value: string | undefined,
): string {
  return options.find((o) => o.value === value)?.label ?? value ?? '—';
}

export function statusLabel(status: string | undefined): string {
  return (status !== undefined ? STATUS_LABELS[status] : undefined) ?? status ?? '—';
}
