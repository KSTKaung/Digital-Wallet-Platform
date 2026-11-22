import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { LOGIN_FAILED_MESSAGE } from '../../api/errorMessages';
import { SESSION_ENDED_MESSAGE } from '../../auth/authContext';
import { getAccessToken, setSession } from '../../auth/tokenStore';
import { errorResponse, jsonResponse, makeJwt, stubFetch, tokenResponse } from '../../test/http';
import { renderRoute } from '../../test/render';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const PASSWORD = 'correct horse battery';

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs(login: Handler = () => tokenResponse(CUSTOMER)) {
  return stubFetch({
    'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    'POST /api/v1/auth/login': login,
    'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }),
  });
}

async function signIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Email'), 'a@example.com');
  await user.type(screen.getByLabelText('Password'), PASSWORD);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  it('signs in, keeps the token in memory only and goes home', async () => {
    const user = userEvent.setup();
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const http = stubs();
    const router = renderRoute('/login');

    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    await signIn(user);

    expect(await screen.findByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/login')[0]!.body!)).toEqual({
      email: 'a@example.com',
      password: PASSWORD,
    });
    expect(getAccessToken()).toBe(CUSTOMER);
    expect(setItem).not.toHaveBeenCalled();
  });

  it('returns to a safe "from" path after signing in', async () => {
    const user = userEvent.setup();
    stubs();
    const router = renderRoute('/login', { from: '/settings/password' });

    await signIn(user);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Change password' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/settings/password');
  });

  it.each(['//evil.example', 'https://evil.example', '/login', '/\\evil.example'])(
    'ignores the unsafe "from" %s and goes home',
    async (from) => {
      const user = userEvent.setup();
      stubs();
      const router = renderRoute('/login', { from });

      await signIn(user);

      expect(await screen.findByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();
      expect(router.state.location.pathname).toBe('/');
    },
  );

  it('lands a compliance officer on /admin, which opens the KYC queue', async () => {
    const user = userEvent.setup();
    const officer = makeJwt({ sub: 'staff-c1', roles: ['COMPLIANCE_OFFICER'], sid: 'sc' });
    stubFetch({
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/login': () => tokenResponse(officer),
      'GET /api/v1/admin/kyc/applications': () =>
        jsonResponse(200, {
          items: [],
          page: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
    });
    const router = renderRoute('/login');

    await signIn(user);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'KYC reviews' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/kyc');
  });

  it.each([
    ['no "from"', undefined, '/admin/customers'],
    ['a customer page', '/transfers', '/admin/customers'],
    ['a staff page', '/admin', '/admin/customers'],
  ])('lands an admin with %s on Wallet freeze', async (_name, from, expected) => {
    const user = userEvent.setup();
    stubs(() => tokenResponse(makeJwt({ sub: 'staff-a1', roles: ['ADMIN'], sid: 'sa' })));
    const router = renderRoute('/login', from === undefined ? undefined : { from });

    await signIn(user);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Wallet freeze' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(expected);
  });

  it('never returns a customer to a staff page', async () => {
    const user = userEvent.setup();
    stubs();
    const router = renderRoute('/login', { from: '/admin/kyc' });

    await signIn(user);

    expect(await screen.findByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });

  it('shows one generic message for every failed sign-in and clears the password', async () => {
    const user = userEvent.setup();
    stubs(() => errorResponse(401, 'UNAUTHORIZED', 'Authentication required'));
    renderRoute('/login');

    await signIn(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(LOGIN_FAILED_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(getAccessToken()).toBeNull();
  });

  it('shows the wait on 429', async () => {
    const user = userEvent.setup();
    stubs(() =>
      jsonResponse(
        429,
        { success: false, errorCode: 'RATE_LIMITED', message: 'Too many requests' },
        { 'Retry-After': '55' },
      ),
    );
    renderRoute('/login');

    await signIn(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Please wait 55 seconds and try again.',
    );
  });

  it('asks for missing fields without sending anything', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/login');

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription('Enter your password.');
    expect(http.callsTo('POST', '/api/v1/auth/login')).toHaveLength(0);
  });

  it('shows the session-ended notice from router state', () => {
    stubs();
    renderRoute('/login', { sessionEnded: true, from: '/transfers' });

    expect(screen.getByRole('status')).toHaveTextContent(SESSION_ENDED_MESSAGE);
  });

  it.each([
    ['passwordChanged', 'Password changed. Sign in again.'],
    ['passwordReset', 'Password reset. Sign in with your new password.'],
  ])('shows the %s notice', (notice, text) => {
    stubs();
    renderRoute('/login', { notice });
    expect(screen.getByRole('status')).toHaveTextContent(text);
  });

  it('shows no notice without router state', () => {
    stubs();
    renderRoute('/login');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('links to registration and password reset', () => {
    stubs();
    renderRoute('/login');
    const main = screen.getByRole('main');

    expect(within(main).getByRole('link', { name: 'Create an account' })).toHaveAttribute(
      'href',
      '/register',
    );
    expect(within(main).getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute(
      'href',
      '/forgot-password',
    );
  });

  it('tells a signed-in visitor they are signed in', () => {
    setSession(CUSTOMER, 900);
    stubs();
    renderRoute('/login');

    expect(screen.getByText(/You are signed in\./)).toBeInTheDocument();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });
});
