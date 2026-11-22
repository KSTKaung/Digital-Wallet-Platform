import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { errorResponse, jsonResponse, makeJwt, stubFetch, tokenResponse } from '../test/http';
import { ApiError } from './client';
import { deposit, freezeWallet, getLimits, moneyJson, withdraw } from './wallet';

const TOKEN = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's-1' });
const IDEM_UUID = '0b7c6a52-3f1d-4c2e-9a8b-1d2e3f4a5b6c';
const MOVEMENT = {
  id: 'a7d1c0de-0000-4000-8000-000000000001',
  walletNumber: '12345678903',
  amount: 150.5,
  status: 'COMPLETED',
  reference: 'DEP-20261120-0001',
  ledgerStatus: 'POSTED',
};
const LIMITS = {
  dailyTransfer: 100000,
  dailyWithdrawal: 50000,
  dailyDeposit: 200000,
  singleTransfer: 50000,
  singleWithdrawal: 30000,
  singleDeposit: 100000,
  minTransfer: 1,
  minWithdrawal: 100,
  minDeposit: 100,
  currency: 'THB',
};

describe('moneyJson', () => {
  it('writes the amount text verbatim as a JSON number', () => {
    expect(moneyJson('150.50', { currency: 'THB' })).toBe('{"amount":150.50,"currency":"THB"}');
    expect(moneyJson('0.30', {})).toBe('{"amount":0.30}');
    expect(JSON.parse(moneyJson('12345678901234567.99', { currency: 'THB' }))).toHaveProperty(
      'currency',
      'THB',
    );
  });

  it('drops undefined fields like JSON.stringify', () => {
    expect(moneyJson('1.00', { currency: 'THB', reference: undefined })).toBe(
      '{"amount":1.00,"currency":"THB"}',
    );
  });

  it.each(['', '-1', '01', '1e3', '1.', 'abc', '1,000', '0.1 ', 'NaN'])(
    'refuses %j, so nothing but a plain decimal reaches the JSON',
    (amount) => {
      expect(() => moneyJson(amount, {})).toThrow('amount must be plain decimal text');
    },
  );
});

describe('wallet API', () => {
  it('reads the limits with the bearer token', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({ 'GET /api/v1/wallet/limits': () => jsonResponse(200, LIMITS) });

    await expect(getLimits()).resolves.toEqual(LIMITS);
    expect(http.calls[0]!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
  });

  it('freezes with a bodiless POST and no Idempotency-Key', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/wallet/freeze': () => jsonResponse(200, { status: 'FROZEN' }),
    });

    await expect(freezeWallet()).resolves.toEqual({ status: 'FROZEN' });
    const [call] = http.calls;
    expect(call!.body).toBeUndefined();
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
    expect(call!.headers.has('Content-Type')).toBe(false);
  });

  it('deposits with the Idempotency-Key, the exact amount and the note', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/wallet/deposits': () => jsonResponse(201, MOVEMENT),
    });

    await expect(
      deposit({ amount: '150.50', currency: 'THB', reference: 'Rent share' }, IDEM_UUID),
    ).resolves.toEqual(MOVEMENT);
    const [call] = http.calls;
    expect(call!.headers.get('Idempotency-Key')).toBe(IDEM_UUID);
    expect(call!.headers.get('Content-Type')).toBe('application/json');
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
    expect(call!.body).toBe('{"amount":150.50,"currency":"THB","reference":"Rent share"}');
  });

  it('deposits without a note when none is given (no fundingSource either)', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({ 'POST /api/v1/wallet/deposits': () => jsonResponse(201, MOVEMENT) });

    await deposit({ amount: '100.00', currency: 'THB' }, IDEM_UUID);
    expect(http.calls[0]!.body).toBe('{"amount":100.00,"currency":"THB"}');
  });

  it('withdraws with the Idempotency-Key and the exact amount', async () => {
    setSession(TOKEN, 900);
    const result = { ...MOVEMENT, reference: 'WDR-20261120-0001' };
    const http = stubFetch({ 'POST /api/v1/wallet/withdrawals': () => jsonResponse(201, result) });

    await expect(withdraw({ amount: '0.30', currency: 'THB' }, IDEM_UUID)).resolves.toEqual(result);
    const [call] = http.calls;
    expect(call!.headers.get('Idempotency-Key')).toBe(IDEM_UUID);
    expect(call!.body).toBe('{"amount":0.30,"currency":"THB"}');
  });

  it('retries after a 401 with the same body and the same key', async () => {
    setSession(TOKEN, 900);
    const fresh = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's-1', n: 2 });
    const http = stubFetch({
      'POST /api/v1/wallet/withdrawals': [
        () => errorResponse(401, 'UNAUTHORIZED'),
        () => jsonResponse(201, MOVEMENT),
      ],
      'POST /api/v1/auth/refresh': () => tokenResponse(fresh),
    });

    await withdraw({ amount: '200.00', currency: 'THB' }, IDEM_UUID);
    const calls = http.callsTo('POST', '/api/v1/wallet/withdrawals');
    expect(calls).toHaveLength(2);
    expect(calls[1]!.headers.get('Idempotency-Key')).toBe(IDEM_UUID);
    expect(calls[1]!.body).toBe(calls[0]!.body);
    expect(calls[1]!.headers.get('Authorization')).toBe(`Bearer ${fresh}`);
  });

  it.each([
    [403, 'KYC_REQUIRED'],
    [409, 'INSUFFICIENT_BALANCE'],
    [409, 'WALLET_FROZEN'],
    [400, 'INVALID_AMOUNT'],
  ])('passes %i %s through as an ApiError', async (status, code) => {
    setSession(TOKEN, 900);
    stubFetch({ 'POST /api/v1/wallet/withdrawals': () => errorResponse(status, code) });

    const failure = withdraw({ amount: '1.00', currency: 'THB' }, IDEM_UUID);
    await expect(failure).rejects.toBeInstanceOf(ApiError);
    await expect(failure).rejects.toEqual(expect.objectContaining({ status, errorCode: code }));
  });

  it('reads Retry-After from a 429', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'POST /api/v1/wallet/deposits': () =>
        jsonResponse(
          429,
          { success: false, errorCode: 'RATE_LIMITED', message: 'Too many', correlationId: 'c' },
          { 'Retry-After': '12' },
        ),
    });

    await expect(deposit({ amount: '1.00', currency: 'THB' }, IDEM_UUID)).rejects.toEqual(
      expect.objectContaining({ status: 429, retryAfterSeconds: 12 }),
    );
  });
});
