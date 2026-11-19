// The caller's own wallet (doc 02 §2.3). Before KYC approval there is none: GET /wallet is
// 404 WALLET_NOT_FOUND and the money endpoints are 403 KYC_REQUIRED (doc 06 MVP-ADR-10); see
// isKycRequired in errorMessages.ts. Step 5 adds the balance, limits and money movements.

import { apiRequest } from './client';
import type { ResponseBodyOf } from './types';

export type Wallet = ResponseBodyOf<'/api/v1/wallet', 'get'>;

export function getWallet(signal?: AbortSignal): Promise<Wallet> {
  return apiRequest<Wallet>('/api/v1/wallet', { signal });
}
