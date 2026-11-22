import { describe, expect, it } from 'vitest';
import { rejectReasonError, reviewStatusLabel, reviewStatusTone } from './kycReviewText';

describe('rejectReasonError (the format checks of RejectKycRequest)', () => {
  it.each([['The photo is not readable'], ['  padded  '], ['ภาพไม่ชัด'], ['1'], ['a'.repeat(500)]])(
    'accepts %j',
    (reason) => {
      expect(rejectReasonError(reason)).toBeUndefined();
    },
  );

  it.each([
    ['empty', '', 'Enter a reason.'],
    ['blank', '   ', 'Enter a reason.'],
    ['501 characters', 'a'.repeat(501), 'Reason must have at most 500 characters.'],
    ['a line break', 'Line one\nline two', 'Reason must be on one line, without line breaks.'],
    ['a carriage return', 'Line one\rline two', 'Reason must be on one line, without line breaks.'],
    [
      'a tab',
      'Photo\tunreadable',
      'Reason must not contain control characters or invisible formatting characters.',
    ],
    [
      'a zero-width space',
      'Photo​unreadable',
      'Reason must not contain control characters or invisible formatting characters.',
    ],
    [
      'a right-to-left mark',
      'Photo‏unreadable',
      'Reason must not contain control characters or invisible formatting characters.',
    ],
    [
      'a lone surrogate',
      'Photo\ud800',
      'Reason must not contain control characters or invisible formatting characters.',
    ],
    ['punctuation only', '?!. -', 'Reason must contain a letter or a digit.'],
  ])('refuses %s', (_name, reason, message) => {
    expect(rejectReasonError(reason)).toBe(message);
  });
});

describe('review status labels', () => {
  it('labels and tones the listable statuses and shows anything else as sent', () => {
    expect(reviewStatusLabel('PENDING_REVIEW')).toBe('Pending review');
    expect(reviewStatusLabel('APPROVED')).toBe('Approved');
    expect(reviewStatusLabel('REJECTED')).toBe('Rejected');
    expect(reviewStatusLabel('ODD')).toBe('ODD');
    expect(reviewStatusLabel(undefined)).toBe('—');
    expect(reviewStatusTone('PENDING_REVIEW')).toBe('warning');
    expect(reviewStatusTone('APPROVED')).toBe('success');
    expect(reviewStatusTone('REJECTED')).toBe('danger');
    expect(reviewStatusTone('ODD')).toBe('neutral');
    expect(reviewStatusTone(undefined)).toBe('neutral');
  });
});
