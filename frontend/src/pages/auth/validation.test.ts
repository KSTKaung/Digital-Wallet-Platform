import { describe, expect, it } from 'vitest';
import {
  collectErrors,
  confirmPasswordError,
  emailError,
  newPasswordError,
  otpError,
  requiredError,
} from './validation';

describe('auth form checks', () => {
  it('checks the password length in code points (12 to 128)', () => {
    expect(newPasswordError('')).toBe('Enter a password.');
    expect(newPasswordError('a'.repeat(11))).toMatch(/12 to 128/);
    expect(newPasswordError('a'.repeat(12))).toBeUndefined();
    expect(newPasswordError('a'.repeat(128))).toBeUndefined();
    expect(newPasswordError('a'.repeat(129))).toMatch(/12 to 128/);
    // 12 emoji are 24 UTF-16 units but 12 code points.
    expect(newPasswordError('\u{1F600}'.repeat(12))).toBeUndefined();
    expect(newPasswordError('\u{1F600}'.repeat(11))).toMatch(/12 to 128/);
  });

  it('checks that the confirmation matches', () => {
    expect(confirmPasswordError('a', '')).toBe('Enter the password again.');
    expect(confirmPasswordError('a', 'b')).toBe('The passwords do not match.');
    expect(confirmPasswordError('a', 'a')).toBeUndefined();
  });

  it('checks the OTP is exactly 6 digits', () => {
    expect(otpError('')).toBe('Enter the 6-digit code.');
    expect(otpError('12345')).toBe('The code is 6 digits.');
    expect(otpError('1234567')).toBe('The code is 6 digits.');
    expect(otpError('12a456')).toBe('The code is 6 digits.');
    expect(otpError(' 123456 ')).toBeUndefined();
  });

  it('checks email presence and shape loosely', () => {
    expect(emailError(' ')).toBe('Enter your email address.');
    expect(emailError('nobody')).toMatch(/like name@example.com/);
    expect(emailError('a@b')).toBeUndefined();
    expect(requiredError(' ', 'Mobile number')).toBe('Enter your mobile number.');
  });

  it('collects only failed checks', () => {
    expect(collectErrors({ a: undefined, b: undefined })).toBeUndefined();
    expect(collectErrors({ a: 'bad', b: undefined })).toEqual({ a: 'bad' });
  });
});
