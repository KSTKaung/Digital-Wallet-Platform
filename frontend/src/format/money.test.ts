import { describe, expect, it } from 'vitest';
import { AMOUNT_MESSAGES, checkAmount, formatAmount, formatMoney } from './money';

describe('formatAmount', () => {
  it.each([
    [1234.5, '1,234.50'],
    [0, '0.00'],
    [0.1, '0.10'],
    [100000, '100,000.00'],
    [1234567.89, '1,234,567.89'],
    ['1234.50', '1,234.50'],
    ['0.00', '0.00'],
    ['007.5', '7.50'],
    ['1.500', '1.50'],
    [' 42 ', '42.00'],
    [-3.5, '-3.50'],
    ['-0.00', '0.00'],
  ])('%j → %s', (value, expected) => {
    expect(formatAmount(value)).toBe(expected);
  });

  it('keeps a non-zero third decimal instead of rounding it', () => {
    expect(formatAmount('1.005')).toBe('1.005');
  });

  it('formats from the decimal text, not float arithmetic', () => {
    // 0.1 + 0.2 in floats; shown as sent, never "rounded" into a nicer value.
    expect(formatAmount(0.30000000000000004)).toBe('0.30000000000000004');
    expect(formatAmount('99999999999999999.99')).toBe('99,999,999,999,999,999.99');
  });

  it('writes exponent forms as plain digits', () => {
    expect(formatAmount(1e21)).toBe('1,000,000,000,000,000,000,000.00');
    expect(formatAmount(1e-7)).toBe('0.0000001');
  });

  it.each([null, undefined, Number.NaN, Number.POSITIVE_INFINITY, 'abc', '1e5', '', '1,000'])(
    'returns null for %j',
    (value) => {
      expect(formatAmount(value)).toBeNull();
    },
  );
});

describe('formatMoney', () => {
  it('adds the currency, THB by default', () => {
    expect(formatMoney(1500.5)).toBe('1,500.50 THB');
    expect(formatMoney('10', 'THB')).toBe('10.00 THB');
  });

  it('leaves the currency out when the API sent none', () => {
    expect(formatMoney(1, null)).toBe('1.00');
  });

  it('shows the fallback for a missing amount', () => {
    expect(formatMoney(undefined)).toBe('—');
    expect(formatMoney(null, 'THB', 'n/a')).toBe('n/a');
  });
});

describe('checkAmount', () => {
  it.each([
    ['1', '1.00'],
    ['1.5', '1.50'],
    ['1.50', '1.50'],
    ['1.500', '1.50'],
    ['1.0000', '1.00'],
    ['0.01', '0.01'],
    ['007.25', '7.25'],
    [' 100 ', '100.00'],
    ['12345678901234567', '12345678901234567.00'],
    ['12345678901234567.99', '12345678901234567.99'],
  ])('accepts %j as %s', (input, amount) => {
    expect(checkAmount(input)).toEqual({ ok: true, amount });
  });

  it.each([
    ['', AMOUNT_MESSAGES.empty],
    ['   ', AMOUNT_MESSAGES.empty],
    ['0', AMOUNT_MESSAGES.notPositive],
    ['0.00', AMOUNT_MESSAGES.notPositive],
    ['000.000', AMOUNT_MESSAGES.notPositive],
    ['-1', AMOUNT_MESSAGES.notPositive],
    ['-0.5', AMOUNT_MESSAGES.notPositive],
    ['1.005', AMOUNT_MESSAGES.decimals],
    ['1.001', AMOUNT_MESSAGES.decimals],
    ['0.0001', AMOUNT_MESSAGES.decimals],
    ['abc', AMOUNT_MESSAGES.format],
    ['1,000', AMOUNT_MESSAGES.format],
    ['1e3', AMOUNT_MESSAGES.format],
    ['+5', AMOUNT_MESSAGES.format],
    ['.5', AMOUNT_MESSAGES.format],
    ['5.', AMOUNT_MESSAGES.format],
    ['1.2.3', AMOUNT_MESSAGES.format],
    ['१२', AMOUNT_MESSAGES.format],
    ['123456789012345678', AMOUNT_MESSAGES.tooLarge],
    ['99999999999999999999999.00', AMOUNT_MESSAGES.tooLarge],
  ])('refuses %j', (input, error) => {
    expect(checkAmount(input)).toEqual({ ok: false, error });
  });

  it('counts integer digits after dropping leading zeros', () => {
    expect(checkAmount('0012345678901234567')).toEqual({
      ok: true,
      amount: '12345678901234567.00',
    });
  });

  it('never repeats the value in a message', () => {
    for (const input of ['1.005', 'abc', '-77', '123456789012345678']) {
      const result = checkAmount(input);
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error).not.toContain(input.replace('-', ''));
      }
    }
  });
});
