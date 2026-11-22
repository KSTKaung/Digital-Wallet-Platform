import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { errorResponse, jsonResponse, stubFetch } from '../test/http';
import {
  APPLICATION_ID,
  APPROVED_DETAIL,
  COMPLIANCE,
  PENDING_DETAIL,
  PENDING_SUMMARY,
  REJECTED_DETAIL,
} from '../test/kycReviewFixtures';
import { page } from '../test/transferFixtures';
import {
  approveKycApplication,
  getKycApplication,
  isKycReviewStatus,
  KYC_REVIEW_STATUSES,
  listKycApplications,
  rejectKycApplication,
} from './admin';
import { ApiError } from './client';

const LIST = '/api/v1/admin/kyc/applications';
const DETAIL = `${LIST}/${APPLICATION_ID}`;

describe('KYC review statuses', () => {
  it('are the three listable statuses, never DRAFT', () => {
    expect(KYC_REVIEW_STATUSES).toEqual(['PENDING_REVIEW', 'APPROVED', 'REJECTED']);
    expect(isKycReviewStatus('APPROVED')).toBe(true);
    expect(isKycReviewStatus('DRAFT')).toBe(false);
    expect(isKycReviewStatus('pending_review')).toBe(false);
    expect(isKycReviewStatus(null)).toBe(false);
    expect(isKycReviewStatus(undefined)).toBe(false);
  });
});

describe('listKycApplications', () => {
  it('asks for PENDING_REVIEW by default, with the 0-based page and size and the bearer', async () => {
    setSession(COMPLIANCE, 900);
    const body = page([PENDING_SUMMARY]);
    const http = stubFetch({ [`GET ${LIST}`]: () => jsonResponse(200, body) });

    await expect(listKycApplications({ page: 0 })).resolves.toEqual(body);
    const [call] = http.calls;
    expect(call!.search).toBe('?page=0&size=20&status=PENDING_REVIEW');
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${COMPLIANCE}`);
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
    expect(call!.body).toBeUndefined();
  });

  it('sends the chosen status and page', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({ [`GET ${LIST}`]: () => jsonResponse(200, page([])) });

    await listKycApplications({ status: 'REJECTED', page: 2, size: 50 });
    expect(http.calls[0]!.search).toBe('?page=2&size=50&status=REJECTED');
  });

  it('surfaces a 403 as an ApiError', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({ [`GET ${LIST}`]: () => errorResponse(403, 'FORBIDDEN') });

    const error = await listKycApplications({ page: 0 }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
  });
});

describe('getKycApplication', () => {
  it('reads one application by id, encoded into the path', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });

    await expect(getKycApplication(APPLICATION_ID)).resolves.toEqual(PENDING_DETAIL);
    expect(http.calls[0]!.method).toBe('GET');
    expect(http.calls[0]!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('surfaces the 404 for a missing application or a draft', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({ [`GET ${DETAIL}`]: () => errorResponse(404, 'NOT_FOUND') });

    const error = await getKycApplication(APPLICATION_ID).catch((e: unknown) => e);
    expect((error as ApiError).status).toBe(404);
  });

  it('keeps a path-like id inside one path segment', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({});

    await getKycApplication('../x?y').catch(() => undefined);
    expect(http.calls[0]!.path).toBe(`${LIST}/..%2Fx%3Fy`);
    expect(http.calls[0]!.search).toBe('');
  });
});

describe('approveKycApplication', () => {
  it('posts to …/approve with no body and no Idempotency-Key and returns the detail', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({
      [`POST ${DETAIL}/approve`]: () => jsonResponse(200, APPROVED_DETAIL),
    });

    await expect(approveKycApplication(APPLICATION_ID)).resolves.toEqual(APPROVED_DETAIL);
    const [call] = http.calls;
    expect(call!.body).toBeUndefined();
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${COMPLIANCE}`);
  });

  it('surfaces 409 INVALID_KYC_STATE', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({ [`POST ${DETAIL}/approve`]: () => errorResponse(409, 'INVALID_KYC_STATE') });

    const error = await approveKycApplication(APPLICATION_ID).catch((e: unknown) => e);
    expect((error as ApiError).errorCode).toBe('INVALID_KYC_STATE');
  });
});

describe('rejectKycApplication', () => {
  it('posts {reason} exactly as typed, as JSON, with no Idempotency-Key', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({ [`POST ${DETAIL}/reject`]: () => jsonResponse(200, REJECTED_DETAIL) });

    await expect(rejectKycApplication(APPLICATION_ID, '  Photo unreadable ')).resolves.toEqual(
      REJECTED_DETAIL,
    );
    const [call] = http.calls;
    expect(JSON.parse(call!.body!)).toEqual({ reason: '  Photo unreadable ' });
    expect(call!.headers.get('Content-Type')).toBe('application/json');
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('surfaces the 400 naming the reason', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({
      [`POST ${DETAIL}/reject`]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'reason: must not contain the document reference'),
    });

    const error = await rejectKycApplication(APPLICATION_ID, 'x').catch((e: unknown) => e);
    expect((error as ApiError).message).toBe('reason: must not contain the document reference');
  });
});
