// The caller's own wallet (doc 02 §2.3). Before KYC approval there is none: GET /wallet is
// 404 WALLET_NOT_FOUND and the money endpoints are 403 KYC_REQUIRED (doc 06 MVP-ADR-10); see
// isKycRequired in errorMessages.ts. Deposits and withdrawals are simulated (MVP-ADR-13).

import { apiRequest } from './client';
import type { RequestBodyOf, ResponseBodyOf } from './types';

export type Wallet = ResponseBodyOf<'/api/v1/wallet', 'get'>;
export type WalletLimits = ResponseBodyOf<'/api/v1/wallet/limits', 'get'>;
export type WalletStatusResult = ResponseBodyOf<'/api/v1/wallet/freeze', 'post'>;
/** `201` body of a deposit or a withdrawal; `reference` is the server's `DEP-…` / `WDR-…`. */
export type MoneyMovement = ResponseBodyOf<'/api/v1/wallet/deposits', 'post', 201>;
export type WithdrawalMovement = ResponseBodyOf<'/api/v1/wallet/withdrawals', 'post', 201>;
/** `{maskedName, maskedWalletNumber}` of a recipient (doc 02 §2.3, MVP-ADR-20). */
export type WalletLookup = ResponseBodyOf<'/api/v1/wallets/lookup', 'get'>;

type DepositBody = RequestBodyOf<'/api/v1/wallet/deposits', 'post'>;
type WithdrawalBody = RequestBodyOf<'/api/v1/wallet/withdrawals', 'post'>;

/**
 * The schema types `amount` as a JSON number. It is passed here as canonical decimal text (from
 * checkAmount) and written into the JSON as a number literal unchanged, so the server's
 * BigDecimal receives exactly the typed value: no float conversion, never 0.30000000000000004.
 * `fundingSource` is left out: the MVP refuses any value (doc 02 §2.3).
 */
export type DepositRequest = Omit<DepositBody, 'amount' | 'fundingSource'> & { amount: string };
export type WithdrawalRequest = Omit<WithdrawalBody, 'amount'> & { amount: string };

/** A JSON number literal: no sign, no leading zeros, no exponent. */
const JSON_DECIMAL = /^(0|[1-9]\d*)(\.\d+)?$/;

/** `{"amount":<literal>, ...rest}` with the amount text written verbatim as a JSON number. */
export function moneyJson(amount: string, rest: Record<string, unknown>): string {
  if (!JSON_DECIMAL.test(amount)) {
    throw new Error('amount must be plain decimal text');
  }
  const tail = JSON.stringify(rest);
  return tail === '{}' ? `{"amount":${amount}}` : `{"amount":${amount},${tail.slice(1)}`;
}

export function getWallet(signal?: AbortSignal): Promise<Wallet> {
  return apiRequest<Wallet>('/api/v1/wallet', { signal });
}

/** Display only (D8): the server applies the limits. */
export function getLimits(signal?: AbortSignal): Promise<WalletLimits> {
  return apiRequest<WalletLimits>('/api/v1/wallet/limits', { signal });
}

/**
 * Finds a recipient by exact wallet number (doc 02 §2.3 "MVP wallet lookup"). The number travels
 * only in the query string, never in a path. A missing, frozen or closed wallet, the caller's own
 * and a wrong check digit are one identical `404`; over the lookup windows it is `429` with
 * `Retry-After`. The caller without a wallet gets `403 KYC_REQUIRED`, a frozen caller
 * `409 WALLET_FROZEN`.
 */
export function lookupWallet(walletNumber: string, signal?: AbortSignal): Promise<WalletLookup> {
  const query = new URLSearchParams({ walletNumber }).toString();
  return apiRequest<WalletLookup>(`/api/v1/wallets/lookup?${query}`, { signal });
}

/** Self-service freeze; repeating it changes nothing. Only staff unfreeze (doc 05 §5.2). */
export function freezeWallet(): Promise<WalletStatusResult> {
  return apiRequest<WalletStatusResult>('/api/v1/wallet/freeze', { method: 'POST' });
}

export function deposit(body: DepositRequest, idempotencyKey: string): Promise<MoneyMovement> {
  const { amount, ...rest } = body;
  return apiRequest<MoneyMovement>('/api/v1/wallet/deposits', {
    method: 'POST',
    body: moneyJson(amount, rest),
    idempotencyKey,
  });
}

export function withdraw(
  body: WithdrawalRequest,
  idempotencyKey: string,
): Promise<WithdrawalMovement> {
  const { amount, ...rest } = body;
  return apiRequest<WithdrawalMovement>('/api/v1/wallet/withdrawals', {
    method: 'POST',
    body: moneyJson(amount, rest),
    idempotencyKey,
  });
}
