// Staff transfer review fixtures shaped like TransferReviewSummaryResponse and
// TransferReviewDetailResponse. Deadlines are far in the past or the future, so the overdue marker
// does not depend on the day the tests run.
import type { TransferReviewDetail, TransferReviewSummary } from '../api/admin';

export const TRANSFER_ID = '3c4d5e6f-7a8b-4c9d-8e0f-a1b2c3d4e5f6';
export const SENDER_ID = '0a1b2c3d-4e5f-4a6b-8c7d-8e9f00112233';
export const RECIPIENT_ID = '1b2c3d4e-5f6a-4b7c-8d9e-9f0011223344';
export const REVIEWER_ID = 'staff-c1';

export const FUTURE_DEADLINE = '2099-01-01T00:00:00Z';
export const PAST_DEADLINE = '2020-01-01T00:00:00Z';

export const PENDING_ITEM: TransferReviewSummary = {
  transferId: TRANSFER_ID,
  reference: 'TRF-20261121-0001',
  amount: 45000,
  currency: 'THB',
  heldAt: '2026-11-21T03:00:00Z',
  reviewExpiresAt: FUTURE_DEADLINE,
  score: 70,
};

export const OVERDUE_ITEM: TransferReviewSummary = {
  ...PENDING_ITEM,
  transferId: '4d5e6f7a-8b9c-4dae-8f10-b2c3d4e5f6a7',
  reference: 'TRF-20191231-0002',
  amount: 1234.5,
  reviewExpiresAt: PAST_DEADLINE,
  score: null,
};

export const PENDING_REVIEW_DETAIL: TransferReviewDetail = {
  transferId: TRANSFER_ID,
  reference: 'TRF-20261121-0001',
  status: 'PENDING_REVIEW',
  amount: 45000,
  currency: 'THB',
  createdAt: '2026-11-21T02:59:00Z',
  heldAt: '2026-11-21T03:00:00Z',
  reviewExpiresAt: FUTURE_DEADLINE,
  decidedAt: null,
  rejectionReasonCategory: null,
  ledgerStatus: null,
  risk: {
    score: 70,
    decision: 'MANUAL_REVIEW',
    assessedAt: '2026-11-21T03:00:00Z',
    rules: [
      { code: 'TRANSFER_AMOUNT', weight: 40 },
      { code: 'NEW_RECIPIENT', weight: 30 },
    ],
  },
  sender: {
    customerId: SENDER_ID,
    firstName: 'Somchai',
    lastName: 'Jaidee',
    maskedWalletNumber: '*******8901',
  },
  recipient: {
    customerId: RECIPIENT_ID,
    firstName: 'Malee',
    lastName: 'Suksan',
    maskedWalletNumber: '*******2345',
  },
  history: [],
};

export const APPROVED_REVIEW_DETAIL: TransferReviewDetail = {
  ...PENDING_REVIEW_DETAIL,
  status: 'COMPLETED',
  decidedAt: '2026-11-21T05:30:00Z',
  ledgerStatus: 'POSTED',
  history: [
    {
      action: 'APPROVE',
      decidedBy: 'STAFF',
      requestedAction: 'APPROVE',
      actorUserId: REVIEWER_ID,
      reasonCategory: null,
      reason: null,
      systemReason: null,
      recordedAt: '2026-11-21T05:30:00Z',
    },
  ],
};

export const REJECTED_REVIEW_DETAIL: TransferReviewDetail = {
  ...PENDING_REVIEW_DETAIL,
  status: 'REJECTED',
  decidedAt: '2026-11-21T05:30:00Z',
  rejectionReasonCategory: 'REVIEW_DECLINED',
  history: [
    {
      action: 'REJECT',
      decidedBy: 'STAFF',
      requestedAction: 'REJECT',
      actorUserId: REVIEWER_ID,
      reasonCategory: 'STAFF_REJECTION',
      reason: 'Pattern matches a known scam',
      systemReason: null,
      recordedAt: '2026-11-21T05:30:00Z',
    },
  ],
};

/** A late approval: the server expired the transfer (MVP-ADR-24 case 6). */
export const EXPIRED_REVIEW_DETAIL: TransferReviewDetail = {
  ...PENDING_REVIEW_DETAIL,
  status: 'EXPIRED',
  reviewExpiresAt: PAST_DEADLINE,
  decidedAt: '2026-11-22T03:00:00Z',
  history: [
    {
      action: 'EXPIRE',
      decidedBy: 'SYSTEM',
      requestedAction: 'APPROVE',
      actorUserId: REVIEWER_ID,
      reasonCategory: null,
      reason: null,
      systemReason: 'REVIEW_DEADLINE_PASSED',
      recordedAt: '2026-11-22T03:00:00Z',
    },
  ],
};
