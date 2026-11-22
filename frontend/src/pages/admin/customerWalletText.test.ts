import { describe, expect, it } from 'vitest';
import {
  customerIdError,
  parseCustomerId,
  resultMessage,
  walletStatusReasonError,
} from './customerWalletText';
import { rejectReasonError } from './kycReviewText';

const ID = '0a1b2c3d-4e5f-4a6b-8c7d-8e9f00112233';

describe('parseCustomerId and customerIdError', () => {
  it.each([
    ['lower case', ID, ID],
    ['upper case', ID.toUpperCase(), ID],
    ['mixed case', '0A1b2C3d-4E5f-4a6B-8c7D-8e9F00112233', ID],
    ['surrounding spaces', `  ${ID}\t `, ID],
    [
      'the nil UUID',
      '00000000-0000-0000-0000-000000000000',
      '00000000-0000-0000-0000-000000000000',
    ],
  ])('accepts %s and sends it trimmed in lower case', (_name, typed, sent) => {
    expect(parseCustomerId(typed)).toBe(sent);
    expect(customerIdError(typed)).toBeUndefined();
  });

  it.each([
    ['empty', ''],
    ['blank', '   '],
  ])('asks for an id when it is %s', (_name, typed) => {
    expect(parseCustomerId(typed)).toBeUndefined();
    expect(customerIdError(typed)).toBe('Enter the customer ID.');
  });

  it.each([
    ['without hyphens', '0a1b2c3d4e5f4a6b8c7d8e9f00112233'],
    ['too short', '0a1b2c3d-4e5f-4a6b-8c7d-8e9f0011223'],
    ['too long', `${ID}0`],
    ['a non-hex digit', '0a1b2c3d-4e5f-4a6b-8c7d-8e9f0011223g'],
    ['braces', `{${ID}}`],
    ['a space inside', '0a1b2c3d-4e5f-4a6b-8c7d 8e9f00112233'],
    ['a path', `${ID}/freeze`],
    ['an email', 'customer@example.com'],
    ['a wallet number', '12345678901'],
  ])('refuses an id %s', (_name, typed) => {
    expect(parseCustomerId(typed)).toBeUndefined();
    expect(customerIdError(typed)).toBe(
      'Customer ID must be a UUID, such as 0a1b2c3d-4e5f-4a6b-8c7d-8e9f00112233.',
    );
  });
});

describe('walletStatusReasonError (the format checks of WalletStatusReasonRequest)', () => {
  it.each([['Suspected fraud'], ['  padded  '], ['ภาพไม่ชัด'], ['1'], ['a'.repeat(500)]])(
    'accepts %j',
    (reason) => {
      expect(walletStatusReasonError(reason)).toBeUndefined();
    },
  );

  it.each([
    ['empty', '', 'Enter a reason.'],
    ['blank', '  \t ', 'Enter a reason.'],
    ['501 characters', 'a'.repeat(501), 'Reason must have at most 500 characters.'],
    [
      'an emoji over the limit (UTF-16 units)',
      `${'a'.repeat(499)}😀`,
      'Reason must have at most 500 characters.',
    ],
    ['a line break', 'Line one\nline two', 'Reason must be on one line, without line breaks.'],
    [
      'a tab',
      'Fraud\treport',
      'Reason must not contain control characters or invisible formatting characters.',
    ],
    [
      'a zero-width space',
      'Fraud​report',
      'Reason must not contain control characters or invisible formatting characters.',
    ],
    ['punctuation only', '?!. -', 'Reason must contain a letter or a digit.'],
  ])('refuses %s', (_name, reason, message) => {
    expect(walletStatusReasonError(reason)).toBe(message);
  });

  it('matches the KYC rejection check, whose backend rules are the same', () => {
    for (const reason of ['', 'ok', 'a'.repeat(501), 'x\ny', '!!', 'x​y']) {
      expect(walletStatusReasonError(reason)).toBe(rejectReasonError(reason));
    }
  });
});

describe('resultMessage', () => {
  it('states the resulting status without claiming that this request changed it', () => {
    const frozen = resultMessage('freeze', 'FROZEN');
    expect(frozen).toBe(
      'Freeze completed. The wallet is frozen (status FROZEN). If it was already frozen, nothing changed and no email was sent.',
    );
    const active = resultMessage('unfreeze', 'ACTIVE');
    expect(active).toBe(
      'Unfreeze completed. The wallet is active (status ACTIVE). If it was already active, nothing changed and no email was sent.',
    );
    expect(frozen).not.toMatch(/\bnow\b/);
    expect(active).not.toMatch(/\bnow\b/);
  });

  it('reports any other status as sent', () => {
    expect(resultMessage('freeze', 'CLOSED')).toBe(
      'The server reports the wallet status as CLOSED, not FROZEN.',
    );
    expect(resultMessage('unfreeze', undefined)).toBe(
      'The server reports the wallet status as unknown, not ACTIVE.',
    );
  });
});
