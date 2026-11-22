// Transfer and history API bodies (doc 02 §2.4 shapes) shared by the transfer tests.

export const RECIPIENT_NUMBER = '98765432109';
export const LOOKUP = { maskedName: 'Somchai J.', maskedWalletNumber: '*******2109' };

export const COMPLETED_TRANSFER = {
  id: '5f0c2b1e-7a3d-4c8e-9b6f-1a2b3c4d5e01',
  reference: 'TRF-0123456789abcdef0123456789abcdef',
  status: 'COMPLETED',
  direction: 'SENT',
  amount: 1500,
  currency: 'THB',
  counterparty: { maskedName: 'Somchai J.', maskedWalletNumber: '*******2109' },
  rejectionReason: null,
  reviewExpiresAt: null,
  // 10:00 Bangkok time.
  createdAt: '2026-11-20T03:00:00Z',
  ledgerStatus: 'POSTED',
};

export const HELD_TRANSFER = {
  ...COMPLETED_TRANSFER,
  id: '5f0c2b1e-7a3d-4c8e-9b6f-1a2b3c4d5e02',
  reference: 'TRF-11111111111111111111111111111111',
  status: 'PENDING_REVIEW',
  // 10:00 Bangkok time the next day.
  reviewExpiresAt: '2026-11-21T03:00:00Z',
  ledgerStatus: null,
};

export const CANCELLED_TRANSFER = { ...HELD_TRANSFER, status: 'CANCELLED', reviewExpiresAt: null };
export const EXPIRED_TRANSFER = { ...HELD_TRANSFER, status: 'EXPIRED', reviewExpiresAt: null };

export const RECEIVED_TRANSFER = {
  ...COMPLETED_TRANSFER,
  id: '5f0c2b1e-7a3d-4c8e-9b6f-1a2b3c4d5e03',
  reference: 'TRF-22222222222222222222222222222222',
  direction: 'RECEIVED',
  amount: 250.5,
  counterparty: { maskedName: 'Malee S.', maskedWalletNumber: '*******5555' },
};

export function page<T>(items: T[], pageIndex = 0, totalElements = items.length, totalPages = 1) {
  return { items, page: { page: pageIndex, size: 20, totalElements, totalPages } };
}
