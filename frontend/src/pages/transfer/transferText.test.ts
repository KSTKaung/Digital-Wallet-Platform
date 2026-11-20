import { describe, expect, it } from 'vitest';
import {
  checkWalletNumber,
  codePointLength,
  counterpartyPrefix,
  counterpartyText,
  DESCRIPTION_MESSAGES,
  descriptionError,
  directionLabel,
  rejectionReasonText,
  signedTransferAmount,
  transferStatusLabel,
  WALLET_NUMBER_MESSAGES,
} from './transferText';

describe('checkWalletNumber', () => {
  it('accepts exactly 11 ASCII digits, without checking the Luhn digit', () => {
    expect(checkWalletNumber('98765432109')).toEqual({ ok: true, walletNumber: '98765432109' });
    // A wrong check digit is the server's to judge (counted like a missing wallet).
    expect(checkWalletNumber('12345678900')).toEqual({ ok: true, walletNumber: '12345678900' });
  });

  it('drops spaces around a pasted number', () => {
    expect(checkWalletNumber('  98765432109 ')).toEqual({ ok: true, walletNumber: '98765432109' });
  });

  it('asks for a number when the field is empty', () => {
    expect(checkWalletNumber('')).toEqual({ ok: false, error: WALLET_NUMBER_MESSAGES.empty });
    expect(checkWalletNumber('   ')).toEqual({ ok: false, error: WALLET_NUMBER_MESSAGES.empty });
  });

  it.each([
    ['1234567890'],
    ['123456789012'],
    ['1234 5678 901'],
    ['1234567890a'],
    ['-1234567890'],
    ['١٢٣٤٥٦٧٨٩٠١'], // Arabic-Indic digits are not ASCII digits
    ['１２３４５６７８９０１'], // full-width digits
  ])('refuses %j', (value) => {
    expect(checkWalletNumber(value)).toEqual({ ok: false, error: WALLET_NUMBER_MESSAGES.format });
  });
});

describe('descriptionError', () => {
  it('accepts an empty description (it is left out of the request)', () => {
    expect(descriptionError('')).toBeUndefined();
  });

  it('counts code points: 140 are fine, 141 are too many', () => {
    expect(descriptionError('a'.repeat(140))).toBeUndefined();
    expect(descriptionError('a'.repeat(141))).toBe(DESCRIPTION_MESSAGES.tooLong);
  });

  it('counts an emoji outside the BMP once, not as two UTF-16 units', () => {
    const text = '\u{1F600}'.repeat(140);
    expect(text.length).toBe(280);
    expect(codePointLength(text)).toBe(140);
    expect(descriptionError(text)).toBeUndefined();
  });

  it('counts a flag emoji as its two code points', () => {
    const flag = '\u{1F1F9}\u{1F1ED}';
    expect(codePointLength(flag)).toBe(2);
    expect(descriptionError(flag.repeat(70))).toBeUndefined();
    expect(descriptionError(`${flag.repeat(70)}a`)).toBe(DESCRIPTION_MESSAGES.tooLong);
  });

  it.each([
    ['a newline', 'Rent\nshare'],
    ['a tab', 'Rent\tshare'],
    ['NUL', 'Rent\u0000'],
    ['DEL', 'Rent\u007f'],
    ['a zero-width space', 'Rent​share'],
    ['a bidi override', '‮Rent'],
    ['a BOM', '﻿Rent'],
    ['a lone surrogate', 'Rent\ud800'],
  ])('refuses %s (Cc, Cf or Cs)', (_name, value) => {
    expect(descriptionError(value)).toBe(DESCRIPTION_MESSAGES.controlCharacters);
  });

  it('keeps leading and trailing spaces: they are part of the request', () => {
    expect(descriptionError('  Lunch  ')).toBeUndefined();
    expect(descriptionError('   ')).toBeUndefined();
  });
});

describe('transfer wording', () => {
  it('labels every documented status and shows an unknown one as sent', () => {
    expect(transferStatusLabel('COMPLETED')).toBe('Completed');
    expect(transferStatusLabel('PENDING_REVIEW')).toBe('Held for review');
    expect(transferStatusLabel('REJECTED')).toBe('Rejected');
    expect(transferStatusLabel('CANCELLED')).toBe('Cancelled');
    expect(transferStatusLabel('EXPIRED')).toBe('Expired');
    expect(transferStatusLabel('BLOCKED')).toBe('Refused');
    expect(transferStatusLabel('NEW_ONE')).toBe('NEW_ONE');
    expect(transferStatusLabel(undefined)).toBe('Unknown');
  });

  it('puts the rejection categories in plain words', () => {
    expect(rejectionReasonText('REVIEW_DECLINED')).toBe('Declined after review.');
    expect(rejectionReasonText('WALLET_UNAVAILABLE')).toMatch(/a wallet could not take part/);
    expect(rejectionReasonText(null)).toBeNull();
    expect(rejectionReasonText('OTHER')).toBe('OTHER');
  });

  it('describes direction and counterparty', () => {
    expect(directionLabel('SENT')).toBe('Sent');
    expect(directionLabel('RECEIVED')).toBe('Received');
    expect(directionLabel(undefined)).toBe('Unknown');
    expect(counterpartyPrefix('SENT')).toBe('To');
    expect(counterpartyPrefix('RECEIVED')).toBe('From');
    expect(counterpartyPrefix('X')).toBe('Other party');
    expect(counterpartyText({ maskedName: 'Somchai J.', maskedWalletNumber: '*******2109' })).toBe(
      'Somchai J. (*******2109)',
    );
    expect(counterpartyText({ maskedName: 'Somchai J.' })).toBe('Somchai J.');
    expect(counterpartyText(null)).toBe('—');
  });

  it('signs the amount by direction, not by colour', () => {
    expect(signedTransferAmount({ amount: 1500, currency: 'THB', direction: 'SENT' })).toBe(
      '−1,500.00 THB',
    );
    expect(signedTransferAmount({ amount: 0.3, currency: 'THB', direction: 'RECEIVED' })).toBe(
      '+0.30 THB',
    );
    expect(signedTransferAmount({ amount: 1, currency: 'THB' })).toBe('1.00 THB');
  });
});
