import { describe, expect, it } from 'vitest';
import { clearSession, setSession } from '../../auth/tokenStore';
import { makeJwt } from '../../test/http';
import { clearRegistration, putRegistration, takeRegistration } from './registrationHandoff';

const HANDOFF = { email: 'a@example.com', password: 'correct horse battery' };

describe('registration hand-off (memory only)', () => {
  it('is one-shot: take() returns the value once, then null', () => {
    putRegistration(HANDOFF);

    expect(takeRegistration()).toEqual(HANDOFF);
    expect(takeRegistration()).toBeNull();
  });

  it('keeps a copy, not the caller object', () => {
    const source = { ...HANDOFF };
    putRegistration(source);
    source.password = 'changed';

    expect(takeRegistration()).toEqual(HANDOFF);
  });

  it('clear() forgets it', () => {
    putRegistration(HANDOFF);
    clearRegistration();

    expect(takeRegistration()).toBeNull();
  });

  it('is dropped when a session starts (sign-in)', () => {
    putRegistration(HANDOFF);
    setSession(makeJwt({ sub: 'u1', roles: ['CUSTOMER'] }), 900);

    expect(takeRegistration()).toBeNull();
  });

  it('is dropped when a session is cleared (logout)', () => {
    setSession(makeJwt({ sub: 'u1', roles: ['CUSTOMER'] }), 900);
    putRegistration(HANDOFF);
    clearSession('loggedOut');

    expect(takeRegistration()).toBeNull();
  });
});
