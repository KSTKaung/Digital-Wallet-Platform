// The customer's own profile (doc 02 §2.2). Email and phone come back masked; the legal name is
// set by an approved KYC application. Date of birth and country are locked from KYC submit until
// a rejection (409 INVALID_KYC_STATE) and must be set before a submit (doc 06 MVP-ADR-10).

import { apiRequest } from './client';
import type { RequestBodyOf, ResponseBodyOf } from './types';

export type CustomerProfile = ResponseBodyOf<'/api/v1/customers/me', 'get'>;
export type UpdateProfileRequest = RequestBodyOf<'/api/v1/customers/me', 'put'>;

const ME = '/api/v1/customers/me';

export function getProfile(signal?: AbortSignal): Promise<CustomerProfile> {
  return apiRequest<CustomerProfile>(ME, { signal });
}

/** `dateOfBirth` as "YYYY-MM-DD", `countryCode` an ISO 3166-1 alpha-2 code in upper case. */
export function updateProfile(request: UpdateProfileRequest): Promise<CustomerProfile> {
  return apiRequest<CustomerProfile>(ME, { method: 'PUT', body: JSON.stringify(request) });
}
