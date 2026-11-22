import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { errorResponse, jsonResponse, makeJwt, stubFetch } from '../test/http';
import { ApiError } from './client';
import { getProfile, updateProfile } from './customer';
import {
  createApplication,
  getApplication,
  getKycStatus,
  submitApplication,
  updateApplication,
  type KycApplicationRequest,
} from './kyc';
import { getWallet } from './wallet';

const TOKEN = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's-1' });
const REQUEST: KycApplicationRequest = {
  verificationLevel: 'BASIC',
  documentType: 'PASSPORT',
  documentReference: 'AB1234567',
  firstName: 'Ada',
  lastName: 'Lovelace',
};
const APPLICATION = { id: 'app-1', status: 'DRAFT', documentReference: '*****4567' };

describe('customer API', () => {
  it('reads the own profile with the bearer token', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'GET /api/v1/customers/me': () => jsonResponse(200, { email: 'a***@example.com' }),
    });

    await expect(getProfile()).resolves.toEqual({ email: 'a***@example.com' });
    expect(http.calls[0]!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`);
  });

  it('puts the date of birth and country', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'PUT /api/v1/customers/me': () => jsonResponse(200, { countryCode: 'TH' }),
    });

    await updateProfile({ dateOfBirth: '1990-05-01', countryCode: 'TH' });
    expect(JSON.parse(http.calls[0]!.body!)).toEqual({
      dateOfBirth: '1990-05-01',
      countryCode: 'TH',
    });
  });

  it('passes a 409 INVALID_KYC_STATE through as an ApiError', async () => {
    setSession(TOKEN, 900);
    stubFetch({ 'PUT /api/v1/customers/me': () => errorResponse(409, 'INVALID_KYC_STATE') });

    await expect(updateProfile({ dateOfBirth: '1990-05-01', countryCode: 'TH' })).rejects.toEqual(
      expect.objectContaining({ status: 409, errorCode: 'INVALID_KYC_STATE' }),
    );
  });
});

describe('KYC API', () => {
  it('creates an application with the documented body', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'POST /api/v1/kyc/applications': () =>
        jsonResponse(201, APPLICATION, { Location: '/api/v1/kyc/applications/app-1' }),
    });

    await expect(createApplication(REQUEST)).resolves.toEqual(APPLICATION);
    const [call] = http.calls;
    expect(JSON.parse(call!.body!)).toEqual(REQUEST);
    expect(call!.headers.get('Content-Type')).toBe('application/json');
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('edits, reads and submits by id, encoding the id', async () => {
    setSession(TOKEN, 900);
    const http = stubFetch({
      'PUT /api/v1/kyc/applications/app-1': () => jsonResponse(200, APPLICATION),
      'GET /api/v1/kyc/applications/app-1': () => jsonResponse(200, APPLICATION),
      'POST /api/v1/kyc/applications/app-1/submit': () =>
        jsonResponse(200, { ...APPLICATION, status: 'PENDING_REVIEW' }),
      'GET /api/v1/kyc/applications/a%2Fb': () => jsonResponse(200, APPLICATION),
    });

    await updateApplication('app-1', REQUEST);
    await getApplication('app-1');
    await expect(submitApplication('app-1')).resolves.toMatchObject({ status: 'PENDING_REVIEW' });
    await getApplication('a/b');

    expect(http.calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'PUT /api/v1/kyc/applications/app-1',
      'GET /api/v1/kyc/applications/app-1',
      'POST /api/v1/kyc/applications/app-1/submit',
      'GET /api/v1/kyc/applications/a%2Fb',
    ]);
    expect(JSON.parse(http.calls[0]!.body!)).toEqual(REQUEST);
    expect(http.calls[2]!.body).toBeUndefined();
  });

  it('reads the status', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'GET /api/v1/kyc/status': () =>
        jsonResponse(200, {
          status: 'NONE',
          applicationId: null,
          verificationLevel: null,
          walletCreated: false,
        }),
    });

    await expect(getKycStatus()).resolves.toEqual({
      status: 'NONE',
      applicationId: null,
      verificationLevel: null,
      walletCreated: false,
    });
  });

  it('reads the id of the application the status reports', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'GET /api/v1/kyc/status': () =>
        jsonResponse(200, {
          status: 'REJECTED',
          applicationId: 'app-1',
          verificationLevel: 'BASIC',
          walletCreated: false,
        }),
    });

    await expect(getKycStatus()).resolves.toMatchObject({
      status: 'REJECTED',
      applicationId: 'app-1',
    });
  });

  it('turns 409 KYC_ALREADY_ACTIVE into an ApiError', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'POST /api/v1/kyc/applications': () => errorResponse(409, 'KYC_ALREADY_ACTIVE'),
    });

    const error = await createApplication(REQUEST).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, errorCode: 'KYC_ALREADY_ACTIVE' });
  });
});

describe('wallet API', () => {
  it('reads the wallet, and a missing one is 404 WALLET_NOT_FOUND', async () => {
    setSession(TOKEN, 900);
    stubFetch({
      'GET /api/v1/wallet': [
        () => jsonResponse(200, { walletNumber: '1234567890' }),
        () => errorResponse(404, 'WALLET_NOT_FOUND'),
      ],
    });

    await expect(getWallet()).resolves.toEqual({ walletNumber: '1234567890' });
    await expect(getWallet()).rejects.toMatchObject({ status: 404, errorCode: 'WALLET_NOT_FOUND' });
  });
});
