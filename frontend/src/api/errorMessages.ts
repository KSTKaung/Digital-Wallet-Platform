// Turns an API error into customer text (doc 05 §5.3 wording where it has an entry). The server's
// own message is shown only for VALIDATION_ERROR, whose message names the field and never the
// value (doc 02 §2.1, GlobalExceptionHandler); every other unknown code gets a generic text, so a
// server message is never echoed blindly. Tokens, passwords and OTPs are never part of the text.

import { ApiError } from './http';

/** What an error notice shows: the text and, when the server sent one, the correlation id. */
export interface DescribedError {
  message: string;
  correlationId?: string;
}

/** Per-screen wording for specific codes, e.g. UNAUTHORIZED on login vs. on change-password. */
export type ErrorOverrides = Partial<Record<string, string>>;

export const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.';
export const NETWORK_ERROR_MESSAGE =
  'The service cannot be reached. Check your connection and try again.';

/** Login: wrong password, unknown account and locked account are one identical 401 (MVP-ADR-23). */
export const LOGIN_FAILED_MESSAGE =
  'Email or password is incorrect, or the account is temporarily locked. Check your details or try again later.';

/**
 * reset-password refused the reset token (`400 INVALID_RESET_TOKEN`: unknown, expired, used or
 * superseded; doc 06 MVP-ADR-19 amendment, doc 02 §2.1).
 */
export const RESET_EXPIRED_MESSAGE =
  'This password reset has expired or was already used. Start again to get a new code.';

const MESSAGES: Readonly<Record<string, string>> = {
  // Authentication (no doc 05 §5.3 entry; wording follows doc 05 §3.2 F-AUTH and MVP-ADR-23).
  INVALID_OTP:
    'The code is wrong or has expired. Check the latest email we sent, or request a new code.',
  INVALID_RESET_TOKEN: RESET_EXPIRED_MESSAGE,
  OTP_EXPIRED: 'The code has expired. Request a new code.',
  OTP_ATTEMPTS_EXCEEDED: 'Too many wrong codes. Request a new code.',
  VERIFICATION_ATTEMPTS_EXCEEDED: 'Too many attempts.',
  // Never says which: the same text as any other failed sign-in (MVP-ADR-23).
  ACCOUNT_LOCKED: LOGIN_FAILED_MESSAGE,
  UNAUTHORIZED: 'Please sign in to continue.',
  FORBIDDEN: 'This action is not available for your account.',
  NOT_FOUND: 'We could not find what you asked for.',
  RATE_LIMITED: 'Too many requests.',
  // doc 05 §5.3.
  KYC_REQUIRED: 'Complete KYC to continue.',
  WALLET_NOT_FOUND: 'Complete KYC to continue.',
  INSUFFICIENT_BALANCE: 'Insufficient balance. Deposit or reduce the amount.',
  DAILY_LIMIT_EXCEEDED: 'Daily limit exceeded. Wait until tomorrow or upgrade your level.',
  WALLET_FROZEN: 'Wallet frozen. Contact support.',
  SERVICE_DEGRADED: 'Service busy, try again.',
  SYSTEM_ERROR: 'Something went wrong on our side. Please try again.',
  // KYC (doc 02 §2.2, MVP-ADR-10; no doc 05 §5.3 entry). Screens override with their context.
  KYC_ALREADY_ACTIVE: 'You already have an active KYC application.',
  INVALID_KYC_STATE: 'This is not possible while your KYC application is in its current state.',
  // Money movements (doc 02 §2.3 error list; no doc 05 §5.3 entry). INVALID_AMOUNT covers the
  // format, the size and the minimum, so the text points at both.
  SINGLE_LIMIT_EXCEEDED:
    'The amount is above the limit for a single transaction. Use a smaller amount.',
  INVALID_AMOUNT:
    'This amount cannot be used. Check the minimum amount and use at most 2 decimal places.',
  CURRENCY_MISMATCH: 'Only Thai baht (THB) is supported.',
  IDEMPOTENCY_KEY_REUSED:
    'This confirmation no longer matches your request. Check the details and confirm again.',
  // Transfers (doc 02 §2.4; no doc 05 §5.3 entry). RISK_BLOCKED never says why (MVP-ADR-24).
  SELF_TRANSFER: 'You cannot send money to your own wallet.',
  RISK_BLOCKED: 'The transfer was refused.',
  INVALID_TRANSFER_STATE:
    'This transfer can no longer be cancelled: it has already been decided or has expired.',
};

/**
 * Staff screens (doc 02 §2.7) pass these as overrides: the customer texts above talk about "your"
 * application or account. INVALID_KYC_STATE on a decision means another officer (or an earlier
 * decision) got there first; FORBIDDEN is the staff role, or a reviewer deciding their own
 * application. The customer texts stay unchanged.
 */
export const STAFF_ERROR_OVERRIDES: ErrorOverrides = {
  INVALID_KYC_STATE:
    'This application is no longer pending review: another officer or an earlier decision has already decided it. The latest details are shown.',
  FORBIDDEN: 'Your staff role does not allow this action.',
  // A transfer review decision (doc 02 §2.7): decided, cancelled or expired meanwhile, or a
  // decision at or after the review deadline, which expires the transfer (MVP-ADR-24 case 6).
  INVALID_TRANSFER_STATE:
    'This transfer is no longer held for review: it has already been decided, cancelled by the sender or expired. A decision at or after the review deadline expires the transfer. The latest details are shown.',
};

/**
 * A transfer review decision: the server answers `403 FORBIDDEN` when the reviewer is the sender or
 * the recipient (MVP-ADR-24), and nothing changes. The same code is the role refusal, which the
 * route guard makes unlikely here, so the text covers both.
 */
export const TRANSFER_DECISION_ERROR_OVERRIDES: ErrorOverrides = {
  ...STAFF_ERROR_OVERRIDES,
  FORBIDDEN:
    'You cannot review a transfer you take part in as the sender or the recipient, and only a compliance officer can decide transfers. Nothing has changed.',
};

/** Codes whose text gets "Please wait N seconds" when the server sent Retry-After. */
const WAIT_CODES: ReadonlySet<string> = new Set([
  'RATE_LIMITED',
  'VERIFICATION_ATTEMPTS_EXCEEDED',
  'SERVICE_DEGRADED',
]);

/** Readable labels for the request fields a validation message can name. */
const FIELD_LABELS: Readonly<Record<string, string>> = {
  email: 'Email',
  identifier: 'Email',
  phoneNumber: 'Mobile number',
  password: 'Password',
  newPassword: 'New password',
  currentPassword: 'Current password',
  otp: 'Code',
  dateOfBirth: 'Date of birth',
  countryCode: 'Country',
  verificationLevel: 'Verification level',
  documentType: 'Document type',
  documentReference: 'Document reference',
  firstName: 'First name',
  lastName: 'Last name',
  amount: 'Amount',
  currency: 'Currency',
  reference: 'Note',
  walletNumber: 'Wallet number',
  receiverWalletNumber: 'Wallet number',
  description: 'Description',
  reason: 'Reason',
  customerId: 'Customer ID',
};

export function describeError(error: unknown, overrides: ErrorOverrides = {}): DescribedError {
  if (!(error instanceof ApiError)) {
    return { message: NETWORK_ERROR_MESSAGE };
  }
  const base = overrides[error.errorCode] ?? baseMessage(error);
  const message = needsWait(error) ? `${base} ${waitText(error.retryAfterSeconds)}` : base;
  return error.correlationId ? { message, correlationId: error.correlationId } : { message };
}

/**
 * `404 WALLET_NOT_FOUND` (GET /wallet) or `403 KYC_REQUIRED` (money endpoints): the customer has
 * no wallet yet and should complete KYC (doc 02 §7 rule 4; doc 06 MVP-ADR-10).
 */
export function isKycRequired(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    ((error.status === 404 && error.errorCode === 'WALLET_NOT_FOUND') ||
      (error.status === 403 && error.errorCode === 'KYC_REQUIRED'))
  );
}

/**
 * The request field a VALIDATION_ERROR names ("dateOfBirth: must not be in the future" →
 * "dateOfBirth"), so a form can mark that field; undefined for any other error.
 */
export function validationErrorField(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.errorCode !== 'VALIDATION_ERROR') {
    return undefined;
  }
  return /^([A-Za-z]+): /.exec(error.message.trim())?.[1];
}

/** "Please wait 30 seconds and try again." or a plain "try again later". */
export function waitText(seconds: number | undefined): string {
  if (seconds === undefined) {
    return 'Please try again later.';
  }
  const rounded = Math.max(1, Math.ceil(seconds));
  return `Please wait ${rounded} ${rounded === 1 ? 'second' : 'seconds'} and try again.`;
}

function needsWait(error: ApiError): boolean {
  return error.status === 429 || error.status === 503 || WAIT_CODES.has(error.errorCode);
}

function baseMessage(error: ApiError): string {
  if (error.errorCode === 'VALIDATION_ERROR') {
    return validationMessage(error.message);
  }
  const known = MESSAGES[error.errorCode];
  if (known) {
    return known;
  }
  if (error.status === 429) {
    return MESSAGES.RATE_LIMITED!;
  }
  if (error.status === 503) {
    return MESSAGES.SERVICE_DEGRADED!;
  }
  return GENERIC_ERROR_MESSAGE;
}

/**
 * "phoneNumber: must match ..." becomes "Mobile number is not in the expected format." and
 * "newPassword: must have 12 to 128 characters" becomes "New password must have 12 to 128
 * characters."; anything that is not "field: text" is shown as sent.
 */
function validationMessage(serverMessage: string): string {
  const match = /^([A-Za-z]+): (.+)$/.exec(serverMessage.trim());
  if (!match) {
    return serverMessage.trim() || 'Please check the form and try again.';
  }
  const [, field, text] = match as unknown as [string, string, string];
  const label = FIELD_LABELS[field] ?? field;
  const detail = text.startsWith('must match') ? 'is not in the expected format' : text;
  return `${label} ${detail.replace(/\.$/, '')}.`;
}
