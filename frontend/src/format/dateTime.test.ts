import { describe, expect, it } from 'vitest';
import {
  bangkokToday,
  formatBangkokDateTime,
  formatBangkokDateTimeLabelled,
  formatCalendarDate,
} from './dateTime';

describe('formatBangkokDateTime', () => {
  it('shows a UTC instant as Bangkok wall-clock time (UTC+7)', () => {
    expect(formatBangkokDateTime('2026-11-19T10:30:00Z')).toBe('19 Nov 2026, 17:30');
  });

  it('moves to the next Bangkok day for a late UTC evening', () => {
    expect(formatBangkokDateTime('2026-11-19T18:05:00Z')).toBe('20 Nov 2026, 01:05');
  });

  it('reads offsets and fractional seconds', () => {
    expect(formatBangkokDateTime('2026-01-01T00:00:00.123456+07:00')).toBe('1 Jan 2026, 00:00');
  });

  it('returns null for missing or unreadable values', () => {
    expect(formatBangkokDateTime(undefined)).toBeNull();
    expect(formatBangkokDateTime(null)).toBeNull();
    expect(formatBangkokDateTime('')).toBeNull();
    expect(formatBangkokDateTime('not a date')).toBeNull();
  });

  it('adds the Bangkok time label', () => {
    expect(formatBangkokDateTimeLabelled('2026-11-19T10:30:00Z')).toBe(
      '19 Nov 2026, 17:30 (Bangkok time)',
    );
    expect(formatBangkokDateTimeLabelled(undefined)).toBeNull();
  });
});

describe('formatCalendarDate', () => {
  it('formats a calendar date without shifting it', () => {
    expect(formatCalendarDate('1990-05-01')).toBe('1 May 1990');
    expect(formatCalendarDate('2000-12-31')).toBe('31 Dec 2000');
  });

  it('returns null for anything that is not a real YYYY-MM-DD date', () => {
    expect(formatCalendarDate(undefined)).toBeNull();
    expect(formatCalendarDate('1990-5-1')).toBeNull();
    expect(formatCalendarDate('1990-02-30')).toBeNull();
  });
});

describe('bangkokToday', () => {
  it('uses the Bangkok day, not the UTC day', () => {
    expect(bangkokToday(new Date('2026-11-19T16:59:59Z'))).toBe('2026-11-19');
    expect(bangkokToday(new Date('2026-11-19T17:00:00Z'))).toBe('2026-11-20');
  });
});
