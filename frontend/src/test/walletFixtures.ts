// Wallet API bodies shared by the dashboard and money-movement tests (doc 02 §2.3 shapes).

export const ACTIVE_WALLET = {
  walletNumber: '12345678903',
  status: 'ACTIVE',
  tier: 'BASIC',
  availableBalance: 1500.5,
  reservedBalance: 200,
  currency: 'THB',
  createdAt: '2026-11-19T10:30:00Z',
};

export const FROZEN_WALLET = { ...ACTIVE_WALLET, status: 'FROZEN' };

/** The MVP-ADR-18 values for a BASIC wallet. */
export const LIMITS = {
  dailyTransfer: 100000,
  dailyWithdrawal: 50000,
  dailyDeposit: 200000,
  singleTransfer: 50000,
  singleWithdrawal: 30000,
  singleDeposit: 100000,
  minTransfer: 1,
  minWithdrawal: 100,
  minDeposit: 100,
  currency: 'THB',
};
