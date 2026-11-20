import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { getAccessToken, setSession } from '../auth/tokenStore';
import {
  deferred,
  emptyResponse,
  errorResponse,
  jsonResponse,
  makeJwt,
  stubFetch,
  tokenResponse,
} from '../test/http';
import { renderRoute } from '../test/render';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const health = () => jsonResponse(200, { status: 'UP' });

function nav() {
  return within(screen.getByRole('navigation', { name: 'Main' }));
}

describe('main navigation by session status', () => {
  it('shows neither set while the session is being restored', async () => {
    const pending = deferred<Response>();
    stubFetch({
      'POST /api/v1/auth/refresh': () => pending.promise,
      'GET /actuator/health': health,
    });
    renderRoute('/');

    expect(nav().getByRole('link', { name: 'Home' })).toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();
    expect(nav().queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();

    pending.resolve(tokenResponse(CUSTOMER));
    expect(await nav().findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(nav().getByRole('link', { name: 'Change password' })).toHaveAttribute(
      'href',
      '/settings/password',
    );
  });

  it('shows Deposit, Withdraw, Profile and KYC to a signed-in customer', async () => {
    setSession(CUSTOMER, 900);
    stubFetch({
      'GET /actuator/health': health,
      'GET /api/v1/wallet': () => errorResponse(404, 'WALLET_NOT_FOUND'),
    });
    renderRoute('/');

    expect(nav().getByRole('link', { name: 'Deposit' })).toHaveAttribute('href', '/deposit');
    expect(nav().getByRole('link', { name: 'Withdraw' })).toHaveAttribute('href', '/withdraw');
    expect(nav().getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/profile');
    expect(nav().getByRole('link', { name: 'KYC' })).toHaveAttribute('href', '/kyc');
    expect(await screen.findByText('Complete KYC to continue.')).toBeInTheDocument();
  });

  it.each([['ADMIN'], ['COMPLIANCE_OFFICER']])('hides the customer links from %s', async (role) => {
    setSession(makeJwt({ sub: 'staff-1', roles: [role], sid: 's9' }), 900);
    stubFetch({ 'GET /actuator/health': health });
    renderRoute('/');

    expect(nav().getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(nav().getByRole('link', { name: 'Change password' })).toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Profile' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'KYC' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Withdraw' })).not.toBeInTheDocument();
    expect(await screen.findByText('Gateway is up')).toBeInTheDocument();
  });

  it('offers Sign in and Register when signed out', async () => {
    stubFetch({
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
      'GET /actuator/health': health,
    });
    renderRoute('/');

    expect(await nav().findByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
    expect(nav().getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register');
    expect(nav().queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Change password' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Profile' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'KYC' })).not.toBeInTheDocument();
  });

  it('signs out once on a double click and returns to the signed-out navigation', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const pending = deferred<Response>();
    const http = stubFetch({
      'POST /api/v1/auth/logout': () => pending.promise,
      'GET /actuator/health': health,
    });
    const router = renderRoute('/');

    await user.dblClick(nav().getByRole('button', { name: 'Sign out' }));
    expect(nav().getByRole('button', { name: 'Signing out…' })).toBeDisabled();
    pending.resolve(emptyResponse(204));

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    const [logout] = http.callsTo('POST', '/api/v1/auth/logout');
    expect(http.callsTo('POST', '/api/v1/auth/logout')).toHaveLength(1);
    expect(logout!.headers.get('Authorization')).toBe(`Bearer ${CUSTOMER}`);
    expect(logout!.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(getAccessToken()).toBeNull();
    expect(nav().getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(nav().queryByRole('button', { name: /Sign out/ })).not.toBeInTheDocument();
  });
});
