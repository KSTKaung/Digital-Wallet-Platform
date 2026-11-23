// The customer's history, read from the ledger (doc 02 §2.4 "MVP history"; doc 06 MVP-ADR-11):
// completed movements only, newest first. Before KYC approval it is `200` with an empty page.

import { apiRequest } from './client';
import { pageQueryString, type PageQuery } from './paging';
import type { ResponseBodyOf } from './types';

export type TransactionPage = ResponseBodyOf<'/api/v1/transactions', 'get'>;
export type Transaction = TransactionPage['items'][number];

/** The documented item types; anything else is shown as sent. */
export const TRANSACTION_TYPES = ['DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT'] as const;

export function listTransactions(query: PageQuery, signal?: AbortSignal): Promise<TransactionPage> {
  return apiRequest<TransactionPage>(`/api/v1/transactions${pageQueryString(query)}`, { signal });
}
