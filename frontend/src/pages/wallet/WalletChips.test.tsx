import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { LedgerStatusChip, WalletStatusChip } from './WalletChips';

describe('LedgerStatusChip', () => {
  it.each([
    ['POSTED', 'Posted'],
    ['PENDING_POST', 'Accounting pending'],
    ['FAILED_POST', 'Accounting issue: contact support'],
    ['SOMETHING_NEW', 'SOMETHING_NEW'],
    [undefined, 'Unknown'],
  ])('renders %s from the response as %j', (status, text) => {
    render(<LedgerStatusChip status={status} />);
    expect(screen.getByText(text)).toBeInTheDocument();
  });
});

describe('WalletStatusChip', () => {
  it.each([
    ['ACTIVE', 'Active'],
    ['FROZEN', 'Frozen'],
    ['CLOSED', 'Closed'],
    ['SUSPENDED', 'SUSPENDED'],
    [undefined, 'Unknown'],
  ])('labels %s as %j', (status, text) => {
    render(<WalletStatusChip status={status} />);
    expect(screen.getByText(text)).toBeInTheDocument();
  });
});
