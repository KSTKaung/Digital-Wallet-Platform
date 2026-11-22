// Minimal KYC for the customer (doc 02 §2.2; doc 06 MVP-ADR-10): level, document type and
// reference, legal name. No upload and no selfie. States DRAFT → PENDING_REVIEW → APPROVED |
// REJECTED; GET /kyc/status says NONE before the first application and returns the id of the
// application it reports (documentations/08 §4D D11). The document reference is always returned
// masked. Request and response bodies are never logged.

import { apiRequest } from './client';
import type { RequestBodyOf, ResponseBodyOf } from './types';

export type KycApplication = ResponseBodyOf<'/api/v1/kyc/applications/{id}', 'get'>;
type GeneratedKycStatus = ResponseBodyOf<'/api/v1/kyc/status', 'get'>;

/**
 * The generated type cannot say that `applicationId` is `null` with status NONE (doc 02 §2.2), so
 * it is widened here; every other field is as generated.
 */
export type KycStatusResponse = Omit<GeneratedKycStatus, 'applicationId'> & {
  applicationId?: string | null;
};

type ApplicationBody = RequestBodyOf<'/api/v1/kyc/applications', 'post'>;

export type VerificationLevel = 'BASIC' | 'FULL';
export type DocumentType = 'ID_CARD' | 'PASSPORT';

/** POST and PUT body, with the enums narrowed to the documented values. */
export type KycApplicationRequest = Omit<ApplicationBody, 'verificationLevel' | 'documentType'> & {
  verificationLevel: VerificationLevel;
  documentType: DocumentType;
};

/** The documented states, plus NONE from /kyc/status (no application yet). */
export const KYC_STATUSES = ['NONE', 'DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'] as const;
export type KycStatus = (typeof KYC_STATUSES)[number];

const APPLICATIONS = '/api/v1/kyc/applications';

function applicationPath(id: string): string {
  return `${APPLICATIONS}/${encodeURIComponent(id)}`;
}

/** 201 with a new DRAFT; 409 KYC_ALREADY_ACTIVE if one is active. */
export function createApplication(request: KycApplicationRequest): Promise<KycApplication> {
  return apiRequest<KycApplication>(APPLICATIONS, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** Replaces a DRAFT; 409 INVALID_KYC_STATE in any other state. */
export function updateApplication(
  id: string,
  request: KycApplicationRequest,
): Promise<KycApplication> {
  return apiRequest<KycApplication>(applicationPath(id), {
    method: 'PUT',
    body: JSON.stringify(request),
  });
}

/** Owner only; another customer's id and a missing one are the same 404. */
export function getApplication(id: string, signal?: AbortSignal): Promise<KycApplication> {
  return apiRequest<KycApplication>(applicationPath(id), { signal });
}

/**
 * DRAFT → PENDING_REVIEW; 409 INVALID_KYC_STATE otherwise; 400 VALIDATION_ERROR when the
 * profile has no date of birth or country yet.
 */
export function submitApplication(id: string): Promise<KycApplication> {
  return apiRequest<KycApplication>(`${applicationPath(id)}/submit`, { method: 'POST' });
}

/**
 * `{status, applicationId, verificationLevel, walletCreated}`: the active application, else the
 * latest rejected one; status NONE and `applicationId: null` without either.
 */
export function getKycStatus(signal?: AbortSignal): Promise<KycStatusResponse> {
  return apiRequest<KycStatusResponse>('/api/v1/kyc/status', { signal });
}
