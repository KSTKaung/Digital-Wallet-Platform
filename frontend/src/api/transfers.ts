// Customer transfers (doc 02 §2.4 "MVP contract decisions"; doc 06 MVP-ADR-03, -20, -24).
// The recipient is found by wallet number (lookupWallet in wallet.ts) and the customer sees the
// masked name and number before confirming. Request and response bodies are never logged; the
// description is stored by the server but never returned.

import { apiRequest } from './client';
import { pageQueryString, type PageQuery } from './paging';
import type { RequestBodyOf, ResponseBodyOf } from './types';
import { moneyJson } from './wallet';

type CreateTransferBody = RequestBodyOf<'/api/v1/transfers', 'post'>;

/**
 * The `Transfer` object (create, list items, detail, cancel). `rejectionReason`,
 * `reviewExpiresAt` and `ledgerStatus` are `null` when they do not apply (doc 02 §2.4).
 */
export type Transfer = ResponseBodyOf<'/api/v1/transfers/{id}', 'get'>;

export type TransferPage = ResponseBodyOf<'/api/v1/transfers', 'get'>;

/**
 * `amount` is canonical decimal text (from checkAmount), written into the JSON as a number literal
 * (see moneyJson); kept local because the schema's `number` would mean a float conversion. `description` is sent exactly as typed, never trimmed; an empty description is
 * left out rather than sent as `""` (doc 02 §2.4: `""` is a different request from no
 * description).
 */
export type CreateTransferRequest = Omit<CreateTransferBody, 'amount'> & { amount: string };

/** The statuses of doc 02 §2.4; anything else is shown as sent. */
export const TRANSFER_STATUSES = [
  'COMPLETED',
  'PENDING_REVIEW',
  'REJECTED',
  'CANCELLED',
  'EXPIRED',
  'BLOCKED',
] as const;

const TRANSFERS = '/api/v1/transfers';

function transferPath(id: string): string {
  return `${TRANSFERS}/${encodeURIComponent(id)}`;
}

/**
 * `201` with `COMPLETED` or `PENDING_REVIEW`; a block is `403 RISK_BLOCKED`. Requires the
 * Idempotency-Key of the confirm step (doc 02 §1.4).
 */
export function createTransfer(
  body: CreateTransferRequest,
  idempotencyKey: string,
): Promise<Transfer> {
  const { amount, description, ...rest } = body;
  const fields = description === undefined || description === '' ? rest : { ...rest, description };
  return apiRequest<Transfer>(TRANSFERS, {
    method: 'POST',
    body: moneyJson(amount, fields),
    idempotencyKey,
  });
}

/** Newest first. The sender sees every status, the receiver only `COMPLETED` ones. */
export function listTransfers(query: PageQuery, signal?: AbortSignal): Promise<TransferPage> {
  return apiRequest<TransferPage>(`${TRANSFERS}${pageQueryString(query)}`, { signal });
}

/** The identical `404` for a missing transfer and one the caller may not see. */
export function getTransfer(id: string, signal?: AbortSignal): Promise<Transfer> {
  return apiRequest<Transfer>(transferPath(id), { signal });
}

/**
 * Cancels a held transfer (sender only, while `PENDING_REVIEW`). No Idempotency-Key: it changes
 * an existing transfer; a repeat is `409 INVALID_TRANSFER_STATE` and changes nothing.
 */
export function cancelTransfer(id: string): Promise<Transfer> {
  return apiRequest<Transfer>(`${transferPath(id)}/cancel`, { method: 'POST' });
}

/** True for a transfer the caller sent that is still held for review (UI only; server decides). */
export function isCancellable(transfer: Transfer): boolean {
  return transfer.direction === 'SENT' && transfer.status === 'PENDING_REVIEW';
}
