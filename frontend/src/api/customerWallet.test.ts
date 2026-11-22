import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { errorResponse, jsonResponse, stubFetch } from '../test/http';
import { ADMIN, CUSTOMER_ID } from '../test/kycReviewFixtures';
import {
  freezeCustomerWallet,
  unfreezeCustomerWallet,
  WALLET_STATUS_REASON_MAX_LENGTH,
} from './admin';
import { ApiError } from './client';

const BASE = `/api/v1/admin/customers/${CUSTOMER_ID}`;

describe.each([
  ['freezeCustomerWallet', freezeCustomerWallet, 'freeze', 'FROZEN'],
  ['unfreezeCustomerWallet', unfreezeCustomerWallet, 'unfreeze', 'ACTIVE'],
] as const)('%s', (_name, call, action, status) => {
  const PATH = `${BASE}/${action}`;

  it('posts {reason} exactly as typed, as JSON, with the bearer and no Idempotency-Key', async () => {
    setSession(ADMIN, 900);
    const http = stubFetch({ [`POST ${PATH}`]: () => jsonResponse(200, { status }) });

    await expect(call(CUSTOMER_ID, '  Suspected fraud ')).resolves.toEqual({ status });
    expect(http.calls).toHaveLength(1);
    const [request] = http.calls;
    expect(request!.method).toBe('POST');
    expect(request!.path).toBe(PATH);
    expect(request!.search).toBe('');
    expect(JSON.parse(request!.body!)).toEqual({ reason: '  Suspected fraud ' });
    expect(request!.headers.get('Content-Type')).toBe('application/json');
    expect(request!.headers.get('Authorization')).toBe(`Bearer ${ADMIN}`);
    expect(request!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('encodes the customer id into the path', async () => {
    setSession(ADMIN, 900);
    const http = stubFetch({});

    await call('a/b?c', 'x').catch(() => undefined);
    expect(http.calls[0]!.path).toBe(`/api/v1/admin/customers/a%2Fb%3Fc/${action}`);
    expect(http.calls[0]!.search).toBe('');
  });

  it.each([
    [404, 'NOT_FOUND', 'Not found'],
    [400, 'VALIDATION_ERROR', 'reason: must not be blank'],
    [403, 'FORBIDDEN', 'Forbidden'],
    [500, 'SYSTEM_ERROR', 'Unexpected error'],
  ])('surfaces a %i %s as an ApiError', async (code, errorCode, message) => {
    setSession(ADMIN, 900);
    stubFetch({ [`POST ${PATH}`]: () => errorResponse(code, errorCode, message) });

    const error = await call(CUSTOMER_ID, 'Reason').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(code);
    expect((error as ApiError).errorCode).toBe(errorCode);
    expect((error as ApiError).message).toBe(message);
  });
});

describe('WALLET_STATUS_REASON_MAX_LENGTH', () => {
  it('is the @Size of WalletStatusReasonRequest', () => {
    expect(WALLET_STATUS_REASON_MAX_LENGTH).toBe(500);
  });
});
