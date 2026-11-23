// Type-level checks of the path-and-method lookups over the generated schema (types.ts). They are
// checked by `npm run typecheck` (tsc -b includes the tests); at run time they only assert true.
import { describe, expectTypeOf, it } from 'vitest';
import type { Schemas, RequestBodyOf, ResponseBodyOf, SuccessStatusOf } from './types';

describe('generated type lookups', () => {
  it('finds the documented success status of each operation', () => {
    expectTypeOf<SuccessStatusOf<'/api/v1/wallet', 'get'>>().toEqualTypeOf<200>();
    expectTypeOf<SuccessStatusOf<'/api/v1/wallet/deposits', 'post'>>().toEqualTypeOf<201>();
    expectTypeOf<SuccessStatusOf<'/api/v1/auth/register', 'post'>>().toEqualTypeOf<201>();
    expectTypeOf<SuccessStatusOf<'/api/v1/auth/forgot-password', 'post'>>().toEqualTypeOf<202>();
    expectTypeOf<SuccessStatusOf<'/api/v1/auth/logout', 'post'>>().toEqualTypeOf<204>();
  });

  it('defaults the response body to the success status, 200 or 201', () => {
    expectTypeOf<ResponseBodyOf<'/api/v1/wallet', 'get'>>().toEqualTypeOf<
      Schemas['WalletResponse']
    >();
    expectTypeOf<ResponseBodyOf<'/api/v1/transfers', 'post'>>().toEqualTypeOf<
      Schemas['TransferResponse']
    >();
    expectTypeOf<ResponseBodyOf<'/api/v1/auth/register', 'post'>>().toEqualTypeOf<
      Schemas['RegisterResponse']
    >();
    expectTypeOf<ResponseBodyOf<'/api/v1/wallet/withdrawals', 'post', 201>>().toEqualTypeOf<
      Schemas['MoneyMovementResponse']
    >();
  });

  it('gives void for a success without a body (202, 204, an empty 200)', () => {
    expectTypeOf<ResponseBodyOf<'/api/v1/auth/resend-otp', 'post'>>().toEqualTypeOf<void>();
    expectTypeOf<ResponseBodyOf<'/api/v1/auth/logout', 'post'>>().toEqualTypeOf<void>();
    expectTypeOf<ResponseBodyOf<'/api/v1/auth/reset-password', 'post'>>().toEqualTypeOf<void>();
  });

  it('rejects a status the operation does not document', () => {
    // @ts-expect-error the deposit answers 201, not 200
    expectTypeOf<ResponseBodyOf<'/api/v1/wallet/deposits', 'post', 200>>().toBeNever();
  });

  it('keeps the nullable and required fields of the document', () => {
    type Transfer = ResponseBodyOf<'/api/v1/transfers/{id}', 'get'>;
    expectTypeOf<Transfer['ledgerStatus']>().toEqualTypeOf<string | null>();
    expectTypeOf<Transfer['reviewExpiresAt']>().toEqualTypeOf<string | null>();
    expectTypeOf<ResponseBodyOf<'/api/v1/kyc/status', 'get'>['applicationId']>().toEqualTypeOf<
      string | null
    >();
    expectTypeOf<ResponseBodyOf<'/api/v1/transactions', 'get'>['items']>().toEqualTypeOf<
      Schemas['TransactionResponse'][]
    >();
  });

  it('finds the JSON request body', () => {
    expectTypeOf<RequestBodyOf<'/api/v1/auth/reset-password', 'post'>>().toEqualTypeOf<
      Schemas['ResetPasswordRequest']
    >();
  });
});
