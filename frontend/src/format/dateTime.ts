// Date and time display (documentations/08 §4D D6; doc 06 MVP-ADR-22). Instants from the API are
// shown in Asia/Bangkok, whatever the browser's own time zone, with a "Bangkok time" label, so
// the customer and the daily limits use the same day. Calendar dates (date of birth) carry no
// time zone and are never shifted.

export const BUSINESS_TIME_ZONE = 'Asia/Bangkok';
export const BANGKOK_TIME_LABEL = 'Bangkok time';

const INSTANT_FORMAT = new Intl.DateTimeFormat('en-GB', {
  timeZone: BUSINESS_TIME_ZONE,
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

const CALENDAR_FORMAT = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
});

const DAY_FORMAT = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function parts(format: Intl.DateTimeFormat, date: Date): Record<string, string> {
  const result: Record<string, string> = {};
  for (const part of format.formatToParts(date)) {
    result[part.type] = part.value;
  }
  return result;
}

function parseInstant(value: string | null | undefined): Date | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * An ISO instant as Bangkok wall-clock time, e.g. "2026-11-19T10:30:00Z" → "19 Nov 2026, 17:30".
 * Built from parts so the text does not depend on the ICU version's separators. Returns null
 * for a missing or unreadable value.
 */
export function formatBangkokDateTime(value: string | null | undefined): string | null {
  const date = parseInstant(value);
  if (!date) {
    return null;
  }
  const p = parts(INSTANT_FORMAT, date);
  return `${p.day} ${p.month} ${p.year}, ${p.hour}:${p.minute}`;
}

/** The same with its label: "19 Nov 2026, 17:30 (Bangkok time)". */
export function formatBangkokDateTimeLabelled(value: string | null | undefined): string | null {
  const text = formatBangkokDateTime(value);
  return text === null ? null : `${text} (${BANGKOK_TIME_LABEL})`;
}

/** A calendar date "YYYY-MM-DD" as "1 May 1990", with no time-zone shift; null if unreadable. */
export function formatCalendarDate(value: string | null | undefined): string | null {
  const match = value ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
  if (!match) {
    return null;
  }
  const [, year, month, day] = match as unknown as [string, string, string, string];
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  if (date.getUTCDate() !== Number(day)) {
    return null;
  }
  const p = parts(CALENDAR_FORMAT, date);
  return `${p.day} ${p.month} ${p.year}`;
}

/** Today's date in Bangkok as "YYYY-MM-DD" (the business day, MVP-ADR-22). */
export function bangkokToday(now: Date = new Date()): string {
  const p = parts(DAY_FORMAT, now);
  return `${p.year}-${p.month}-${p.day}`;
}
