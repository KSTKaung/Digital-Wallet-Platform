import { StatusChip, type ChipTone } from '../../components/StatusChip';

/** Wallet statuses of doc 02 §2.3 (`ACTIVE|FROZEN|CLOSED`); anything else is shown as sent. */
const WALLET_STATUS: Readonly<Record<string, { label: string; tone: ChipTone }>> = {
  ACTIVE: { label: 'Active', tone: 'success' },
  FROZEN: { label: 'Frozen', tone: 'danger' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
};

export function WalletStatusChip({ status }: { status: string | undefined }) {
  const known = status ? WALLET_STATUS[status] : undefined;
  return (
    <StatusChip tone={known?.tone ?? 'neutral'}>{known?.label ?? status ?? 'Unknown'}</StatusChip>
  );
}

/**
 * `ledgerStatus` from a money response, rendered from the value sent (doc 02 §7 rule 5; doc 05
 * §5.3). The MVP always answers `POSTED` (MVP-ADR-03), but the value is never assumed.
 */
export function LedgerStatusChip({ status }: { status: string | undefined }) {
  switch (status) {
    case 'POSTED':
      return <StatusChip tone="success">Posted</StatusChip>;
    case 'PENDING_POST':
      return <StatusChip tone="warning">Accounting pending</StatusChip>;
    case 'FAILED_POST':
      return <StatusChip tone="danger">Accounting issue: contact support</StatusChip>;
    default:
      return <StatusChip tone="neutral">{status ?? 'Unknown'}</StatusChip>;
  }
}
