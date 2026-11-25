// Staff (back-office) endpoints under /api/v1/admin (doc 02 §2.7; doc 06 MVP-ADR-10, -12, -24).
// The server admits only the right role to each path (SecurityConfig): KYC and transfer review
// decisions need COMPLIANCE_OFFICER, wallet freeze and unfreeze need ADMIN. None of these takes an
// Idempotency-Key: a review decision decides an existing record, and a repeat is `409` and changes
// nothing; a repeated freeze or unfreeze is `200` with the current status and changes nothing.
// Request and response bodies are never logged.

import { apiRequest } from './client';
import { pageQueryString, type PageQuery } from './paging';
import type { RequestBodyOf, ResponseBodyOf } from './types';

export type KycReviewPage = ResponseBodyOf<'/api/v1/admin/kyc/applications', 'get'>;

/**
 * One queue row: legal name and document reference masked by the server; `decidedAt` is `null`
 * while the application is pending.
 */
export type KycReviewSummary = KycReviewPage['items'][number];

/**
 * The reviewer's view: legal name in full, document reference masked, date of birth and country
 * as copied onto the application at submit. The decision fields are `null` until it is decided.
 */
export type KycReviewDetail = ResponseBodyOf<'/api/v1/admin/kyc/applications/{id}', 'get'>;

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

export type TransferReviewPage = ResponseBodyOf<'/api/v1/admin/transfer-reviews', 'get'>;

/**
 * One queue row (`PENDING_REVIEW` only, soonest deadline first; overdue ones stay until expired).
 * `score` is a nullable Integer on the server (TransferReviewSummaryResponse).
 */
export type TransferReviewSummary = TransferReviewPage['items'][number];

/**
 * The staff detail of a transfer that was ever held for review (doc 02 §2.7 "As built").
 * `decidedAt`, `rejectionReasonCategory` and `ledgerStatus` are `null` while they do not apply.
 */
export type TransferReviewDetail = ResponseBodyOf<
  '/api/v1/admin/transfer-reviews/{transferId}',
  'get'
>;

/**
 * The review outcome (0 or 1 items). Which facts are `null` depends on the outcome:
 * `requestedAction` and `actorUserId` for the expiry job, `reasonCategory` and `reason` except for
 * a staff rejection, `systemReason` except for a system outcome.
 */
export type TransferReviewHistory = TransferReviewDetail['history'][number];

/** The size of the reviewer's reason (ReviewOutcome.MAX_REASON_LENGTH, in code points). */
export const TRANSFER_REJECTION_REASON_MAX_LENGTH = 500;

const TRANSFER_REVIEWS = '/api/v1/admin/transfer-reviews';

function transferReviewPath(transferId: string): string {
  return `${TRANSFER_REVIEWS}/${encodeURIComponent(transferId)}`;
}

/** Held transfers, soonest review deadline first, then by id (doc 02 §2.7). */
export function listTransferReviews(
  query: PageQuery,
  signal?: AbortSignal,
): Promise<TransferReviewPage> {
  return apiRequest<TransferReviewPage>(`${TRANSFER_REVIEWS}${pageQueryString(query)}`, {
    signal,
  });
}

/** A missing id and a transfer that was never held are the same `404`; a malformed id is `400`. */
export function getTransferReview(
  transferId: string,
  signal?: AbortSignal,
): Promise<TransferReviewDetail> {
  return apiRequest<TransferReviewDetail>(transferReviewPath(transferId), { signal });
}

/**
 * PENDING_REVIEW → COMPLETED (or REJECTED with WALLET_UNAVAILABLE if a wallet is frozen or
 * closed). `200` with the detail as committed; `403 FORBIDDEN` when the reviewer is the sender or
 * the recipient (nothing changes); `409 INVALID_TRANSFER_STATE` when it is no longer held, or when
 * the deadline had passed (the transfer is then expired).
 */
export function approveTransferReview(transferId: string): Promise<TransferReviewDetail> {
  return apiRequest<TransferReviewDetail>(`${transferReviewPath(transferId)}/approve`, {
    method: 'POST',
  });
}

/**
 * PENDING_REVIEW → REJECTED (REVIEW_DECLINED), the held amount back to the sender. The reason is
 * staff-only and required: before the deadline a blank or invalid one is `400 VALIDATION_ERROR`
 * naming `reason`. Other errors as approveTransferReview. The reason is sent as typed.
 */
export function rejectTransferReview(
  transferId: string,
  reason: string,
): Promise<TransferReviewDetail> {
  return apiRequest<TransferReviewDetail>(`${transferReviewPath(transferId)}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

type CustomerFreezePath = '/api/v1/admin/customers/{customerId}/freeze';
type CustomerUnfreezePath = '/api/v1/admin/customers/{customerId}/unfreeze';

/**
 * `{status}` after a staff freeze or unfreeze (CustomerWalletStatusResponse): no balance and no
 * wallet number. `ACTIVE` or `FROZEN` in the MVP; shown as sent.
 */
export type CustomerWalletStatus = ResponseBodyOf<CustomerFreezePath, 'post'> &
  ResponseBodyOf<CustomerUnfreezePath, 'post'>;

type WalletStatusReasonRequest = RequestBodyOf<CustomerFreezePath, 'post'> &
  RequestBodyOf<CustomerUnfreezePath, 'post'>;

/** The size of the freeze and unfreeze reason (WalletStatusReasonRequest @Size, UTF-16 units). */
export const WALLET_STATUS_REASON_MAX_LENGTH = 500;

function changeWalletStatus(
  customerId: string,
  action: 'freeze' | 'unfreeze',
  reason: string,
): Promise<CustomerWalletStatus> {
  const body: WalletStatusReasonRequest = { reason };
  return apiRequest<CustomerWalletStatus>(
    `/api/v1/admin/customers/${encodeURIComponent(customerId)}/${action}`,
    { method: 'POST', body: JSON.stringify(body) },
  );
}

/**
 * ADMIN freezes the customer's wallet and its ledger account in one transaction (doc 02 §2.7; doc
 * 06 MVP-ADR-10). `200 {status}`. Freezing a `FROZEN` wallet is also `200` and changes nothing,
 * so the response cannot say whether this call changed the wallet. The identical `404` for an
 * unknown customer and one without a wallet; `400 VALIDATION_ERROR` naming `reason` (or
 * `customerId` for an id that is not a UUID). The reason is sent as typed and audited; the
 * customer's email never contains it.
 */
export function freezeCustomerWallet(
  customerId: string,
  reason: string,
): Promise<CustomerWalletStatus> {
  return changeWalletStatus(customerId, 'freeze', reason);
}

/** ADMIN unfreezes: the only way back to `ACTIVE`. Otherwise as freezeCustomerWallet. */
export function unfreezeCustomerWallet(
  customerId: string,
  reason: string,
): Promise<CustomerWalletStatus> {
  return changeWalletStatus(customerId, 'unfreeze', reason);
}
