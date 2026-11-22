// Staff (back-office) endpoints under /api/v1/admin (doc 02 §2.7; doc 06 MVP-ADR-10, -12). The
// server admits only the right role to each path (SecurityConfig): KYC decisions need
// COMPLIANCE_OFFICER. None of these takes an Idempotency-Key: they decide an existing record, and
// a repeat is `409` and changes nothing. Request and response bodies are never logged.

import { apiRequest } from './client';
import { pageQueryString, type PageQuery } from './paging';
import type { ResponseBodyOf } from './types';

type GeneratedKycReviewPage = ResponseBodyOf<'/api/v1/admin/kyc/applications', 'get'>;
type GeneratedKycReviewSummary = NonNullable<GeneratedKycReviewPage['items']>[number];
type GeneratedKycReviewDetail = ResponseBodyOf<'/api/v1/admin/kyc/applications/{id}', 'get'>;

/**
 * One queue row: legal name and document reference masked by the server. The generated type
 * cannot say that `decidedAt` is `null` while the application is pending, so it is widened.
 */
export type KycReviewSummary = Omit<GeneratedKycReviewSummary, 'decidedAt'> & {
  decidedAt?: string | null;
};

export type KycReviewPage = Omit<GeneratedKycReviewPage, 'items'> & { items?: KycReviewSummary[] };

/**
 * The reviewer's view: legal name in full, document reference masked, date of birth and country
 * as copied onto the application at submit. The decision fields are `null` until it is decided.
 */
export type KycReviewDetail = Omit<
  GeneratedKycReviewDetail,
  'decidedAt' | 'reviewerId' | 'rejectionReason'
> & {
  decidedAt?: string | null;
  reviewerId?: string | null;
  rejectionReason?: string | null;
};

/** The statuses the queue can be filtered by (KycReviewService.LISTABLE); drafts are never listed. */
export const KYC_REVIEW_STATUSES = ['PENDING_REVIEW', 'APPROVED', 'REJECTED'] as const;
export type KycReviewStatus = (typeof KYC_REVIEW_STATUSES)[number];
export const DEFAULT_KYC_REVIEW_STATUS: KycReviewStatus = 'PENDING_REVIEW';

export function isKycReviewStatus(value: unknown): value is KycReviewStatus {
  return typeof value === 'string' && (KYC_REVIEW_STATUSES as readonly string[]).includes(value);
}

/** The size of the rejection reason (RejectKycRequest, V8's column). */
export const KYC_REJECTION_REASON_MAX_LENGTH = 500;

const KYC_APPLICATIONS = '/api/v1/admin/kyc/applications';

function kycApplicationPath(id: string): string {
  return `${KYC_APPLICATIONS}/${encodeURIComponent(id)}`;
}

export interface KycReviewQuery extends PageQuery {
  /** Default PENDING_REVIEW. */
  status?: KycReviewStatus;
}

/** Submitted applications in `status`, oldest submission first (doc 02 §2.7). */
export function listKycApplications(
  { status = DEFAULT_KYC_REVIEW_STATUS, ...page }: KycReviewQuery,
  signal?: AbortSignal,
): Promise<KycReviewPage> {
  const query = `${pageQueryString(page)}&${new URLSearchParams({ status }).toString()}`;
  return apiRequest<KycReviewPage>(`${KYC_APPLICATIONS}${query}`, { signal });
}

/** A missing application and a draft are the same `404`. */
export function getKycApplication(id: string, signal?: AbortSignal): Promise<KycReviewDetail> {
  return apiRequest<KycReviewDetail>(kycApplicationPath(id), { signal });
}

/**
 * PENDING_REVIEW → APPROVED and the customer's wallet is created, in one transaction. `200` with
 * the detail; `409 INVALID_KYC_STATE` when it is no longer pending.
 */
export function approveKycApplication(id: string): Promise<KycReviewDetail> {
  return apiRequest<KycReviewDetail>(`${kycApplicationPath(id)}/approve`, { method: 'POST' });
}

/**
 * PENDING_REVIEW → REJECTED with the reason the customer will see. `200` with the detail;
 * `400 VALIDATION_ERROR` naming `reason` (also when it contains the document reference);
 * `409 INVALID_KYC_STATE` when it is no longer pending. The reason is sent as typed.
 */
export function rejectKycApplication(id: string, reason: string): Promise<KycReviewDetail> {
  return apiRequest<KycReviewDetail>(`${kycApplicationPath(id)}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}
