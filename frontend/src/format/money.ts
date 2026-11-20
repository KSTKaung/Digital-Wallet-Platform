// Money display and amount input (doc 06 MVP-ADR-21; documentations/08 §4D D8). THB only, two
// decimals, never rounded. Amounts from the API are JSON numbers with two decimals (doc 02 §2.3);
// they are formatted from their decimal text, so no float arithmetic touches them. An amount the
// customer types is checked for its FORMAT only and kept as text, so the request carries exactly
// what was typed; limits and minimums are the server's decision.

export const CURRENCY = 'THB';

/** Integer digits that `NUMERIC(19,2)` holds (doc 02 §2.3, error 2: "amount: is too large"). */
export const MAX_INTEGER_DIGITS = 17;

const PLAIN_DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

/** Plain digits for numbers that String() writes with an exponent (≥ 1e21 or < 1e-6). */
const PLAIN_NUMBER = new Intl.NumberFormat('en-US', {
  useGrouping: false,
  maximumFractionDigits: 20,
});

/**
 * A JS number or decimal string as plain decimal text ("1234.5", "-3"), or null if it is not a
 * finite amount. `String(number)` gives the shortest text that reads back as the same number, so
 * 0.1 stays "0.1"; only exponent forms (never a NUMERIC(19,2) amount) go through Intl.
 */
function decimalText(value: number | string): string | null {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      return null;
    }
    const text = String(value);
    return /e/i.test(text) ? PLAIN_NUMBER.format(value) : text;
  }
  const trimmed = value.trim();
  return PLAIN_DECIMAL.test(trimmed) ? trimmed : null;
}

function groupThousands(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/**
 * "1234.5" or 1234.5 → "1,234.50"; null/undefined/unreadable → null. At least two decimals are
 * shown; extra zero decimals are dropped, a non-zero third decimal is kept rather than rounded.
 */
export function formatAmount(value: number | string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const text = decimalText(value);
  const match = text === null ? null : PLAIN_DECIMAL.exec(text);
  if (!match) {
    return null;
  }
  const [, sign = '', rawInteger = '0', rawFraction = ''] = match;
  const integer = rawInteger.replace(/^0+(?=\d)/, '');
  const fraction = rawFraction.replace(/0+$/, '').padEnd(2, '0');
  const negative = sign === '-' && /[1-9]/.test(integer + fraction);
  return `${negative ? '-' : ''}${groupThousands(integer)}.${fraction}`;
}

/** "1,234.50 THB", or `fallback` when the amount is missing or unreadable. */
export function formatMoney(
  value: number | string | null | undefined,
  currency: string | null | undefined = CURRENCY,
  fallback = '—',
): string {
  const amount = formatAmount(value);
  if (amount === null) {
    return fallback;
  }
  return currency ? `${amount} ${currency}` : amount;
}

export type AmountCheck = { ok: true; amount: string } | { ok: false; error: string };

export const AMOUNT_MESSAGES = {
  empty: 'Enter an amount.',
  format: 'Amount must use digits only, with an optional decimal point, for example 150.50.',
  notPositive: 'Amount must be more than 0.',
  decimals: 'Amount can have at most 2 decimal places.',
  tooLarge: 'Amount has too many digits.',
} as const;

/**
 * Checks the format of a typed amount (D8): digits with an optional decimal point, more than 0,
 * at most 2 decimal places (a non-zero third decimal is refused, trailing zeros are accepted,
 * MVP-ADR-21), at most 17 integer digits. Returns the amount as canonical decimal text with
 * exactly two decimals ("007.5" → "7.50", "1.500" → "1.50"): leading zeros and trailing zero
 * decimals are dropped, nothing is rounded. Messages name the field, never the value.
 */
export function checkAmount(raw: string): AmountCheck {
  const input = raw.trim();
  if (input === '') {
    return { ok: false, error: AMOUNT_MESSAGES.empty };
  }
  if (/^-\d/.test(input)) {
    return { ok: false, error: AMOUNT_MESSAGES.notPositive };
  }
  const match = /^(\d+)(?:\.(\d+))?$/.exec(input);
  if (!match) {
    return { ok: false, error: AMOUNT_MESSAGES.format };
  }
  const [, rawInteger = '', rawFraction = ''] = match;
  if (/[1-9]/.test(rawFraction.slice(2))) {
    return { ok: false, error: AMOUNT_MESSAGES.decimals };
  }
  const integer = rawInteger.replace(/^0+(?=\d)/, '');
  if (integer.length > MAX_INTEGER_DIGITS) {
    return { ok: false, error: AMOUNT_MESSAGES.tooLarge };
  }
  const fraction = rawFraction.slice(0, 2).padEnd(2, '0');
  if (!/[1-9]/.test(integer + fraction)) {
    return { ok: false, error: AMOUNT_MESSAGES.notPositive };
  }
  return { ok: true, amount: `${integer}.${fraction}` };
}
