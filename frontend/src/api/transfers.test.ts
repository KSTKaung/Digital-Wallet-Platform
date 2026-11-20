import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { errorResponse, jsonResponse, makeJwt, stubFetch } from '../test/http';
import {
  COMPLETED_TRANSFER,
  HELD_TRANSFER,
  LOOKUP,
  page,
  RECEIVED_TRANSFER,
  RECIPIENT_NUMBER,
} from '../test/transferFixtures';
import { ApiError } from './client';
import { pageQueryString } from './paging';
import { listTransactions } from './transactions';
import {
  cancelTransfer,
  createTransfer,
  getTransfer,
  isCancellable,
  listTransfers,
} from './transfers';
import { lookupWallet } from './wallet';

const TOKEN = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's-1' });
const KEY = '0b7c6a52-3f1d-4c2e-9a8b-1d2e3f4a5b6c';

describe('pageQueryString', () => {
  it('writes the 0-based page and the size, 20 by default', () => {
    expect(pageQueryString({ page: 0 })).toBe('?page=0&size=20');
    expect(pageQueryString({ page: 3, size: 50 })).toBe('?page=3&size=50');
  });
});

describe('lookupWallet', () => {
  it('sends the number only in the query string, with the bearer token', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({ 'GET /api/v1/wallets/lookup': () => jsonResponse(200, LOOKUP) });

    await expect(lookupWallet(RECIPIENT_NUMBER)).resolves.toEqual(LOOKUP);
    const [call] = http.calls;
    expect(call!.path).toBe('/api/v1/wallets/lookup');
    expect(call!.search).toBe(`?walletNumber=${RECIPIENT_NUMBER}`);
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
    expect(call!.body).toBeUndefined();
  });

  it('encodes whatever it is given, so nothing leaks into the path', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({ 'GET /api/v1/wallets/lookup': () => jsonResponse(200, LOOKUP) });

    await lookupWallet('1/2&x=3');
    expect(http.calls[0]!.path).toBe('/api/v1/wallets/lookup');
    expect(http.calls[0]!.search).toBe('?walletNumber=1%2F2%26x%3D3');
  });

  it('surfaces a 429 with its Retry-After seconds', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'GET /api/v1/wallets/lookup': () =>
        jsonResponse(
          429,
          { success: false, errorCode: 'RATE_LIMITED', message: 'x' },
          { 'Retry-After': '42' },
        ),
    });

    const error = await lookupWallet(RECIPIENT_NUMBER).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).retryAfterSeconds).toBe(42);
  });
});

describe('createTransfer', () => {
  it('posts the amount as a literal, the Idempotency-Key and no description when none is given', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/transfers': () => jsonResponse(201, COMPLETED_TRANSFER),
    });

    await expect(
      createTransfer(
        { receiverWalletNumber: RECIPIENT_NUMBER, amount: '1500.00', currency: 'THB' },
        KEY,
      ),
    ).resolves.toEqual(COMPLETED_TRANSFER);
    const [call] = http.calls;
    expect(call!.headers.get('Idempotency-Key')).toBe(KEY);
    expect(call!.headers.get('Content-Type')).toBe('application/json');
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
    expect(call!.body).toBe(
      `{"amount":1500.00,"receiverWalletNumber":"${RECIPIENT_NUMBER}","currency":"THB"}`,
    );
  });

  it('leaves an empty description out rather than sending ""', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/transfers': () => jsonResponse(201, COMPLETED_TRANSFER),
    });

    await createTransfer(
      { receiverWalletNumber: RECIPIENT_NUMBER, amount: '0.30', currency: 'THB', description: '' },
      KEY,
    );
    expect(http.calls[0]!.body).toBe(
      `{"amount":0.30,"receiverWalletNumber":"${RECIPIENT_NUMBER}","currency":"THB"}`,
    );
    expect(http.calls[0]!.body).not.toContain('description');
  });

  it('sends the description exactly as typed, spaces and emoji included', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/transfers': () => jsonResponse(201, COMPLETED_TRANSFER),
    });

    await createTransfer(
      {
        receiverWalletNumber: RECIPIENT_NUMBER,
        amount: '12.50',
        currency: 'THB',
        description: '  Lunch \u{1F1F9}\u{1F1ED} ',
      },
      KEY,
    );
    const body = http.calls[0]!.body!;
    expect(body.startsWith('{"amount":12.50,')).toBe(true);
    expect(JSON.parse(body)).toEqual({
      amount: 12.5,
      receiverWalletNumber: RECIPIENT_NUMBER,
      currency: 'THB',
      description: '  Lunch \u{1F1F9}\u{1F1ED} ',
    });
  });

  it('refuses an amount that is not plain decimal text before any request', () => {
    const http = stubFetch({});
    expect(() =>
      createTransfer(
        { receiverWalletNumber: RECIPIENT_NUMBER, amount: '1e3', currency: 'THB' },
        KEY,
      ),
    ).toThrow('amount must be plain decimal text');
    expect(http.calls).toHaveLength(0);
  });

  it('surfaces 403 RISK_BLOCKED as an ApiError', async () => {
    setSession(TOKEN, 900);
    stubFetch({ 'POST /api/v1/transfers': () => errorResponse(403, 'RISK_BLOCKED') });

    await expect(
      createTransfer(
        { receiverWalletNumber: RECIPIENT_NUMBER, amount: '1.00', currency: 'THB' },
        KEY,
      ),
    ).rejects.toMatchObject({ status: 403, errorCode: 'RISK_BLOCKED' });
  });
});

describe('transfer reads and cancel', () => {
  it('lists a page with the 0-based page and the size', async () => {
    setSession(TOKEN, 900);
    const body = page([COMPLETED_TRANSFER], 2, 41, 3);
    const http = stubFetch({ 'GET /api/v1/transfers': () => jsonResponse(200, body) });

    await expect(listTransfers({ page: 2 })).resolves.toEqual(body);
    expect(http.calls[0]!.search).toBe('?page=2&size=20');
  });

  it('reads one transfer by its encoded id', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      [`GET /api/v1/transfers/${HELD_TRANSFER.id}`]: () => jsonResponse(200, HELD_TRANSFER),
    });

    await expect(getTransfer(HELD_TRANSFER.id)).resolves.toEqual(HELD_TRANSFER);
    expect(http.calls[0]!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
  });

  it('encodes an id so it cannot reach another path', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({});

    await getTransfer('../wallet').catch(() => undefined);
    expect(http.calls[0]!.path).toBe('/api/v1/transfers/..%2Fwallet');
  });

  it('cancels with a bodiless POST and no Idempotency-Key', async () => {
    setSession(TOKEN, 900);
    const cancelled = { ...HELD_TRANSFER, status: 'CANCELLED', reviewExpiresAt: null };
    const http = stubFetch({
      [`POST /api/v1/transfers/${HELD_TRANSFER.id}/cancel`]: () => jsonResponse(200, cancelled),
    });

    await expect(cancelTransfer(HELD_TRANSFER.id)).resolves.toEqual(cancelled);
    const [call] = http.calls;
    expect(call!.body).toBeUndefined();
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
    expect(call!.headers.has('Content-Type')).toBe(false);
  });

  it('isCancellable: only a SENT transfer that is PENDING_REVIEW', () => {
    expect(isCancellable(HELD_TRANSFER)).toBe(true);
    expect(isCancellable({ ...HELD_TRANSFER, direction: 'RECEIVED' })).toBe(false);
    expect(isCancellable(COMPLETED_TRANSFER)).toBe(false);
    expect(isCancellable(RECEIVED_TRANSFER)).toBe(false);
    expect(isCancellable({ ...HELD_TRANSFER, status: 'EXPIRED' })).toBe(false);
  });
});

describe('listTransactions', () => {
  it('reads the history page with the bearer token', async () => {
    setSession(TOKEN, 900);
    const body = page([
      {
        reference: 'DEP-1',
        type: 'DEPOSIT',
        amount: 100,
        currency: 'THB',
        postedAt: '2026-11-20T03:00:00Z',
        ledgerStatus: 'POSTED',
      },
    ]);
    const http = stubFetch({ 'GET /api/v1/transactions': () => jsonResponse(200, body) });

    await expect(listTransactions({ page: 0 })).resolves.toEqual(body);
    expect(http.calls[0]!.search).toBe('?page=0&size=20');
    expect(http.calls[0]!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
  });
});
