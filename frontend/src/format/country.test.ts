import { describe, expect, it } from 'vitest';
import { countryName } from './country';

describe('countryName', () => {
  it('names a known code', () => {
    expect(countryName('TH')).toBe('Thailand (TH)');
  });

  it('falls back to the code, or undefined when empty', () => {
    expect(countryName('not a code')).toBe('not a code');
    expect(countryName(undefined)).toBeUndefined();
    expect(countryName('')).toBeUndefined();
  });
});
