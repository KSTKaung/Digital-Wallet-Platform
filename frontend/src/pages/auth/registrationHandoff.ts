// Memory-only hand-off from /register to /verify. verify-otp REGISTRATION needs the password
// (MVP-ADR-23), so /register keeps the email and password here for the moment between its 201
// and /verify's first render. Nothing goes into router state, history.state, the URL or
// storage, so a reload or a direct visit to /verify finds nothing and shows the full form.

import { subscribe } from '../../auth/tokenStore';

export interface RegistrationHandoff {
  readonly email: string;
  readonly password: string;
}

let pending: RegistrationHandoff | null = null;

/** Keeps the just-registered credentials for /verify (replaces any earlier hand-off). */
export function putRegistration(handoff: RegistrationHandoff): void {
  pending = { email: handoff.email, password: handoff.password };
}

/** Returns the hand-off once and forgets it; later calls return null. */
export function takeRegistration(): RegistrationHandoff | null {
  const value = pending;
  pending = null;
  return value;
}

export function clearRegistration(): void {
  pending = null;
}

// Signing in or out (any session change) drops an unused hand-off.
subscribe(clearRegistration);
