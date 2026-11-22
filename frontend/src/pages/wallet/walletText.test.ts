import { describe, expect, it } from 'vitest';
import {
  canMoveMoney,
  CLOSED_EXPLANATION,
  FROZEN_EXPLANATION,
  movementStatusLabel,
  NOT_ACTIVE_EXPLANATION,
  NOTE_MESSAGES,
  noteError,
  notActiveExplanation,
  tierLabel,
} from './walletText';

describe('noteError', () => {
  it.each(['', 'Rent', 'ค่าเช่า', '42', 'Top-up #3'])('accepts %j', (note) => {
    expect(noteError(note)).toBeUndefined();
  });

  it('refuses a note without a letter or digit', () => {
    expect(noteError('---')).toBe(NOTE_MESSAGES.noLetterOrDigit);
  });

  it.each(['a\u0000b', 'tab\there', 'zero​width'])(
    'refuses control and format characters in %j',
    (note) => {
      expect(noteError(note)).toBe(NOTE_MESSAGES.controlCharacters);
    },
  );

  it('refuses more than 100 characters', () => {
    expect(noteError('a'.repeat(100))).toBeUndefined();
    expect(noteError('a'.repeat(101))).toBe(NOTE_MESSAGES.tooLong);
  });
});

describe('wallet status text', () => {
  it('lets only an active wallet move money (UI only)', () => {
    expect(canMoveMoney('ACTIVE')).toBe(true);
    expect(canMoveMoney('FROZEN')).toBe(false);
    expect(canMoveMoney(undefined)).toBe(false);
  });

  it('explains each state that cannot move money', () => {
    expect(notActiveExplanation('ACTIVE')).toBeNull();
    expect(notActiveExplanation('FROZEN')).toBe(FROZEN_EXPLANATION);
    expect(FROZEN_EXPLANATION).toMatch(/Only our staff can unfreeze/);
    expect(notActiveExplanation('CLOSED')).toBe(CLOSED_EXPLANATION);
    expect(notActiveExplanation('SOMETHING')).toBe(NOT_ACTIVE_EXPLANATION);
  });

  it('labels tiers and movement statuses, showing unknown values as sent', () => {
    expect(tierLabel('BASIC')).toBe('Basic');
    expect(tierLabel('VERIFIED')).toBe('Verified');
    expect(tierLabel('GOLD')).toBe('GOLD');
    expect(tierLabel(undefined)).toBe('—');
    expect(movementStatusLabel('COMPLETED')).toBe('Completed');
    expect(movementStatusLabel('PENDING')).toBe('PENDING');
    expect(movementStatusLabel(undefined)).toBe('Unknown');
  });
});
