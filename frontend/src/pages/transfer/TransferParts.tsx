import type { Transfer } from '../../api/transfers';
import { BangkokTime } from '../../components/BangkokTime';
import { StatusChip, type ChipTone } from '../../components/StatusChip';
import { LedgerStatusChip } from '../wallet/WalletChips';
import {
  counterpartyPrefix,
  counterpartyText,
  directionLabel,
  rejectionReasonText,
  signedTransferAmount,
  transferStatusLabel,
} from './transferText';

const STATUS_TONES: Readonly<Record<string, ChipTone>> = {
  COMPLETED: 'success',
  PENDING_REVIEW: 'warning',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
  EXPIRED: 'neutral',
  BLOCKED: 'danger',
};

/** A transfer status in words (the tone is never the only signal). */
export function TransferStatusChip({ status }: { status: string | undefined }) {
  return (
    <StatusChip tone={(status && STATUS_TONES[status]) || 'neutral'}>
      {transferStatusLabel(status)}
    </StatusChip>
  );
}

/**
 * A transfer's `ledgerStatus`, rendered from the value sent: `POSTED` for a completed transfer,
 * `null` while nothing is posted (held, rejected, cancelled, expired, blocked; doc 02 §2.4).
 */
export function TransferLedger({ status }: { status: string | null | undefined }) {
  if (status === null || status === undefined) {
    return (
      <span>
        <span aria-hidden="true">—</span>
        <span className="sr-only">Not posted</span>
      </span>
    );
  }
  return <LedgerStatusChip status={status} />;
}

/** Every customer-visible field of one transfer, as a description list. */
export function TransferSummary({ transfer }: { transfer: Transfer }) {
  const rejection =
    transfer.status === 'REJECTED' ? rejectionReasonText(transfer.rejectionReason) : null;
  return (
    <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
      <dt className="font-medium">Status</dt>
      <dd>
        <TransferStatusChip status={transfer.status} />
      </dd>
      {rejection && (
        <>
          <dt className="font-medium">Reason</dt>
          <dd>{rejection}</dd>
        </>
      )}
      <dt className="font-medium">Direction</dt>
      <dd>{directionLabel(transfer.direction)}</dd>
      <dt className="font-medium">{counterpartyPrefix(transfer.direction)}</dt>
      <dd>{counterpartyText(transfer.counterparty)}</dd>
      <dt className="font-medium">Amount</dt>
      <dd>{signedTransferAmount(transfer)}</dd>
      <dt className="font-medium">Reference</dt>
      <dd className="break-all">{transfer.reference ?? '—'}</dd>
      <dt className="font-medium">Created</dt>
      <dd>
        <BangkokTime value={transfer.createdAt} />
      </dd>
      {transfer.status === 'PENDING_REVIEW' && (
        <>
          <dt className="font-medium">Review deadline</dt>
          <dd>
            <BangkokTime value={transfer.reviewExpiresAt} />
          </dd>
        </>
      )}
      <dt className="font-medium">Ledger</dt>
      <dd>
        <TransferLedger status={transfer.ledgerStatus} />
      </dd>
    </dl>
  );
}
