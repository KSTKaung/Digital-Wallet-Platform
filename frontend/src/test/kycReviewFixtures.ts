// Staff KYC review fixtures shaped like KycReviewSummaryResponse and KycReviewDetailResponse.
import type { KycReviewDetail, KycReviewSummary } from '../api/admin';
import { makeJwt } from './http';

export const COMPLIANCE = makeJwt({ sub: 'staff-c1', roles: ['COMPLIANCE_OFFICER'], sid: 'sc' });
export const ADMIN = makeJwt({ sub: 'staff-a1', roles: ['ADMIN'], sid: 'sa' });
export const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });

export const APPLICATION_ID = '7d1e2f3a-4b5c-4d6e-8f70-112233445566';
export const CUSTOMER_ID = '0a1b2c3d-4e5f-4a6b-8c7d-8e9f00112233';
export const REVIEWER_ID = 'staff-c1';
export const MASKED_REFERENCE = '*****4567';

export const PENDING_SUMMARY: KycReviewSummary = {
  id: APPLICATION_ID,
  customerId: CUSTOMER_ID,
  status: 'PENDING_REVIEW',
  verificationLevel: 'BASIC',
  documentType: 'ID_CARD',
  documentReference: MASKED_REFERENCE,
  maskedName: 'Somchai J.',
  submittedAt: '2026-11-20T03:00:00Z',
  decidedAt: null,
  createdAt: '2026-11-19T03:00:00Z',
};

export const APPROVED_SUMMARY: KycReviewSummary = {
  ...PENDING_SUMMARY,
  id: '8e2f3a4b-5c6d-4e7f-8a81-223344556677',
  status: 'APPROVED',
  verificationLevel: 'FULL',
  documentType: 'PASSPORT',
  maskedName: 'Malee S.',
  decidedAt: '2026-11-21T05:30:00Z',
};

export const PENDING_DETAIL: KycReviewDetail = {
  id: APPLICATION_ID,
  customerId: CUSTOMER_ID,
  status: 'PENDING_REVIEW',
  verificationLevel: 'BASIC',
  documentType: 'ID_CARD',
  documentReference: MASKED_REFERENCE,
  firstName: 'Somchai',
  lastName: 'Jaidee',
  dateOfBirth: '1990-05-01',
  countryCode: 'TH',
  reviewerId: null,
  rejectionReason: null,
  submittedAt: '2026-11-20T03:00:00Z',
  decidedAt: null,
  createdAt: '2026-11-19T03:00:00Z',
  updatedAt: '2026-11-20T03:00:00Z',
};

export const APPROVED_DETAIL: KycReviewDetail = {
  ...PENDING_DETAIL,
  status: 'APPROVED',
  reviewerId: REVIEWER_ID,
  decidedAt: '2026-11-21T05:30:00Z',
  updatedAt: '2026-11-21T05:30:00Z',
};

export const REJECTED_DETAIL: KycReviewDetail = {
  ...PENDING_DETAIL,
  status: 'REJECTED',
  reviewerId: REVIEWER_ID,
  rejectionReason: 'The photo is not readable',
  decidedAt: '2026-11-21T05:30:00Z',
  updatedAt: '2026-11-21T05:30:00Z',
};
