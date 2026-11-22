import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { errorResponse, jsonResponse, stubFetch } from '../test/http';
import { COMPLIANCE } from '../test/kycReviewFixtures';
import { page } from '../test/transferFixtures';
import {
  APPROVED_REVIEW_DETAIL,
  PENDING_ITEM,
  PENDING_REVIEW_DETAIL,
  REJECTED_REVIEW_DETAIL,
  TRANSFER_ID,
} from '../test/transferReviewFixtures';
import {
  approveTransferReview,
  getTransferReview,
  listTransferReviews,
  rejectTransferReview,
  TRANSFER_REJECTION_REASON_MAX_LENGTH,
} from './admin';
import { ApiError } from './client';

const LIST = '/api/v1/admin/transfer-reviews';
const DETAIL = `${LIST}/${TRANSFER_ID}`;

describe('listTransferReviews', () => {
  it('asks for the 0-based page and size with the bearer, no body and no Idempotency-Key', async () => {
    setSession(COMPLIANCE, 900);
    const body = page([PENDING_ITEM]);
    const http = stubFetch({ [`GET ${LIST}`]: () => jsonResponse(200, body) });

    await expect(listTransferReviews({ page: 0 })).resolves.toEqual(body);
    const [call] = http.calls;
    expect(call!.method).toBe('GET');
    expect(call!.search).toBe('?page=0&size=20');
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${COMPLIANCE}`);
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
    expect(call!.body).toBeUndefined();
  });

  it('sends the chosen page and size', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({ [`GET ${LIST}`]: () => jsonResponse(200, page([])) });

    await listTransferReviews({ page: 3, size: 100 });
    expect(http.calls[0]!.search).toBe('?page=3&size=100');
  });

  it('surfaces a 403 as an ApiError', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({ [`GET ${LIST}`]: () => errorResponse(403, 'FORBIDDEN') });

    const error = await listTransferReviews({ page: 0 }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
  });
});

describe('getTransferReview', () => {
  it('reads one transfer by id', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });

    await expect(getTransferReview(TRANSFER_ID)).resolves.toEqual(PENDING_REVIEW_DETAIL);
    expect(http.calls[0]!.method).toBe('GET');
    expect(http.calls[0]!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('surfaces the 404 for a transfer that was never held', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({ [`GET ${DETAIL}`]: () => errorResponse(404, 'NOT_FOUND') });

    const error = await getTransferReview(TRANSFER_ID).catch((e: unknown) => e);
    expect((error as ApiError).status).toBe(404);
  });

  it('keeps a path-like id inside one path segment', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({});

    await getTransferReview('../x?y').catch(() => undefined);
    expect(http.calls[0]!.path).toBe(`${LIST}/..%2Fx%3Fy`);
    expect(http.calls[0]!.search).toBe('');
  });
});

describe('approveTransferReview', () => {
  it('posts to …/approve with no body and no Idempotency-Key and returns the detail', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({
      [`POST ${DETAIL}/approve`]: () => jsonResponse(200, APPROVED_REVIEW_DETAIL),
    });

    await expect(approveTransferReview(TRANSFER_ID)).resolves.toEqual(APPROVED_REVIEW_DETAIL);
    const [call] = http.calls;
    expect(call!.body).toBeUndefined();
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${COMPLIANCE}`);
  });

  it.each([
    [403, 'FORBIDDEN'],
    [409, 'INVALID_TRANSFER_STATE'],
  ])('surfaces %i %s', async (status, code) => {
    setSession(COMPLIANCE, 900);
    stubFetch({ [`POST ${DETAIL}/approve`]: () => errorResponse(status, code) });

    const error = await approveTransferReview(TRANSFER_ID).catch((e: unknown) => e);
    expect((error as ApiError).status).toBe(status);
    expect((error as ApiError).errorCode).toBe(code);
  });
});

describe('rejectTransferReview', () => {
  it('posts {reason} exactly as typed, as JSON, with no Idempotency-Key', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubFetch({
      [`POST ${DETAIL}/reject`]: () => jsonResponse(200, REJECTED_REVIEW_DETAIL),
    });

    await expect(rejectTransferReview(TRANSFER_ID, '  Known scam pattern ')).resolves.toEqual(
      REJECTED_REVIEW_DETAIL,
    );
    const [call] = http.calls;
    expect(call!.method).toBe('POST');
    expect(JSON.parse(call!.body!)).toEqual({ reason: '  Known scam pattern ' });
    expect(call!.headers.get('Content-Type')).toBe('application/json');
    expect(call!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('surfaces the 400 naming the reason', async () => {
    setSession(COMPLIANCE, 900);
    stubFetch({
      [`POST ${DETAIL}/reject`]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'reason: must not be blank'),
    });

    const error = await rejectTransferReview(TRANSFER_ID, ' ').catch((e: unknown) => e);
    expect((error as ApiError).message).toBe('reason: must not be blank');
  });

  it('uses the server reason size', () => {
    expect(TRANSFER_REJECTION_REASON_MAX_LENGTH).toBe(500);
  });
});
