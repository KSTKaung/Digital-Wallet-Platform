import { describe, expect, it } from 'vitest';
import {
  decidedByLabel,
  isOverdue,
  reasonCategoryLabel,
  rejectionCategoryLabel,
  rejectTransferReasonError,
  requestedActionLabel,
  reviewActionLabel,
  riskDecisionLabel,
  ruleLabel,
  systemReasonLabel,
} from './transferReviewText';

const CONTROL = 'Reason must not contain control characters or invisible formatting characters.';
// Built from code points, so no invisible character is written into this file.
const ch = (codePoint: number) => String.fromCodePoint(codePoint);
const EMOJI = ch(0x1f600);
const ZERO_WIDTH_SPACE = ch(0x200b);
const BOM = ch(0xfeff);
const NO_BREAK_SPACE = ch(0xa0);
const LONE_SURROGATE = String.fromCharCode(0xd800);
const UNIT_SEPARATOR = ch(0x1f);
const THAI = [0x0e22, 0x0e2d, 0x0e14, 0x0e40, 0x0e07, 0x0e34, 0x0e19].map(ch).join('');

describe('labels', () => {
  it('names the known codes and shows any other value as sent', () => {
    expect(ruleLabel('TRANSFER_AMOUNT')).toBe('Transfer amount');
    expect(ruleLabel('TRANSFER_FREQUENCY')).toBe('Transfer frequency');
    expect(ruleLabel('NEW_RECIPIENT')).toBe('New recipient');
    expect(ruleLabel('NEW_SENDER_WALLET')).toBe('New sender wallet');
    expect(ruleLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(riskDecisionLabel('MANUAL_REVIEW')).toBe('Manual review');
    expect(reviewActionLabel('EXPIRE')).toBe('Expired');
    expect(decidedByLabel('CUSTOMER')).toBe('Customer (sender)');
    expect(requestedActionLabel('CANCEL')).toBe('Cancel');
    expect(reasonCategoryLabel('STAFF_REJECTION')).toBe('Staff rejection');
    expect(systemReasonLabel('RECIPIENT_WALLET_FROZEN')).toBe("Recipient's wallet frozen");
    expect(rejectionCategoryLabel('WALLET_UNAVAILABLE')).toBe('Wallet unavailable');
  });

  it('shows a dash for a missing value', () => {
    expect(systemReasonLabel(null)).toBe('—');
    expect(requestedActionLabel(undefined)).toBe('—');
    expect(reasonCategoryLabel('')).toBe('—');
  });
});

describe('isOverdue', () => {
  const now = Date.parse('2026-11-22T00:00:00Z');

  it('is true at and after the deadline, false before it or without one', () => {
    expect(isOverdue('2026-11-22T00:00:00Z', now)).toBe(true);
    expect(isOverdue('2026-11-21T23:59:59Z', now)).toBe(true);
    expect(isOverdue('2026-11-22T00:00:01Z', now)).toBe(false);
    expect(isOverdue(null, now)).toBe(false);
    expect(isOverdue(undefined, now)).toBe(false);
    expect(isOverdue('not a time', now)).toBe(false);
  });
});

describe('rejectTransferReasonError (TextRules.requireReason)', () => {
  it.each([
    ['empty', '', 'Enter a reason.'],
    ['whitespace', '   \t ', 'Enter a reason.'],
    ['line breaks only', '\n\n', 'Enter a reason.'],
    ['an inner line break', 'Known\nscam', 'Reason must be on one line, without line breaks.'],
    ['an inner tab', 'Known\tscam', CONTROL],
    ['a zero-width space', `Known${ZERO_WIDTH_SPACE}scam`, CONTROL],
    ['a leading BOM (Java strip keeps it)', `${BOM}Known scam`, CONTROL],
    ['a lone surrogate', `Known ${LONE_SURROGATE} scam`, CONTROL],
    ['501 characters', 'a'.repeat(501), 'Reason must have at most 500 characters.'],
    [
      '501 emoji (1002 UTF-16 units)',
      EMOJI.repeat(501),
      'Reason must have at most 500 characters.',
    ],
    ['punctuation only', '?!.', 'Reason must contain a letter or a digit.'],
    [
      'a no-break space (Java strip keeps it)',
      NO_BREAK_SPACE,
      'Reason must contain a letter or a digit.',
    ],
  ])('refuses %s', (_name, reason, message) => {
    expect(rejectTransferReasonError(reason)).toBe(message);
  });

  it.each([
    ['plain text', 'Known scam pattern'],
    ['surrounding spaces', '  Known scam pattern  '],
    ['a trailing line break (stripped)', 'Known scam pattern\n'],
    ['a leading U+001F (Java strip removes it)', `${UNIT_SEPARATOR}Known scam`],
    ['500 characters after stripping', ` ${'a'.repeat(500)} `],
    ['500 code points of emoji', `${EMOJI.repeat(499)}a`],
    ['Thai text', THAI],
    ['digits only', '42'],
  ])('accepts %s, as the server would', (_name, reason) => {
    expect(rejectTransferReasonError(reason)).toBeUndefined();
  });
});
