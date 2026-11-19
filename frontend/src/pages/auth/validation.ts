// Client-side checks for the auth forms: presence, password length and match, OTP shape. They
// only save a round trip; the server applies the real rules (doc 02 §2.1, MVP-ADR-23) and its
// answer always wins (e.g. "must not repeat a recent password" is only known to the server).

/** Documented password length (doc 05 §3.2 F-AUTH, MVP-ADR-23), counted in code points after NFC. */
export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_LENGTH = 128;
/** Documented OTP length and resend cooldown (MVP-ADR-23). */
export const OTP_LENGTH = 6;
export const RESEND_COOLDOWN_SECONDS = 60;
/** The size of auth.users.email; longer values are refused by the server. */
export const EMAIL_MAX_LENGTH = 254;

export const PASSWORD_HINT = `${PASSWORD_MIN_LENGTH} to ${PASSWORD_MAX_LENGTH} characters.`;
export const NEW_PASSWORD_HINT = `${PASSWORD_HINT} It cannot be one of your last 5 passwords.`;

export function requiredError(value: string, label: string): string | undefined {
  return value.trim() === '' ? `Enter your ${label.toLowerCase()}.` : undefined;
}

export function emailError(value: string): string | undefined {
  if (value.trim() === '') {
    return 'Enter your email address.';
  }
  if (!value.includes('@')) {
    return 'Enter an email address like name@example.com.';
  }
  return undefined;
}

export function newPasswordError(value: string): string | undefined {
  const length = [...value.normalize('NFC')].length;
  if (length === 0) {
    return 'Enter a password.';
  }
  if (length < PASSWORD_MIN_LENGTH || length > PASSWORD_MAX_LENGTH) {
    return `Password must be ${PASSWORD_MIN_LENGTH} to ${PASSWORD_MAX_LENGTH} characters.`;
  }
  return undefined;
}

export function confirmPasswordError(password: string, confirm: string): string | undefined {
  if (confirm === '') {
    return 'Enter the password again.';
  }
  return confirm === password ? undefined : 'The passwords do not match.';
}

export function otpError(value: string): string | undefined {
  if (value.trim() === '') {
    return 'Enter the 6-digit code.';
  }
  return new RegExp(`^[0-9]{${OTP_LENGTH}}$`).test(value.trim())
    ? undefined
    : 'The code is 6 digits.';
}

/** Keeps the first error per field; returns undefined when every field passed. */
export function collectErrors<K extends string>(
  checks: Partial<Record<K, string | undefined>>,
): Partial<Record<K, string>> | undefined {
  const errors: Partial<Record<K, string>> = {};
  let failed = false;
  for (const key of Object.keys(checks) as K[]) {
    const message = checks[key];
    if (message) {
      errors[key] = message;
      failed = true;
    }
  }
  return failed ? errors : undefined;
}

export const OTP_HINT =
  'The 6-digit code from our email. A code expires 5 minutes after it is sent.';
