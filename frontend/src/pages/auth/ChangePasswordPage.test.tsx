import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { SESSION_ENDED_MESSAGE } from '../../auth/authContext';
import { getAccessToken, setSession } from '../../auth/tokenStore';
import { deferred, emptyResponse, errorResponse, makeJwt, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { WRONG_CURRENT_PASSWORD_MESSAGE } from './ChangePasswordPage';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const CURRENT = 'the old passphrase';
const NEW_PASSWORD = 'a brand new passphrase';

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs(change: Handler = () => emptyResponse(200)) {
  return stubFetch({
    'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    'POST /api/v1/auth/change-password': change,
  });
}

async function fill(user: ReturnType<typeof userEvent.setup>, confirm = NEW_PASSWORD) {
  await user.type(screen.getByLabelText('Current password'), CURRENT);
  await user.type(screen.getByLabelText('New password'), NEW_PASSWORD);
  await user.type(screen.getByLabelText('Confirm new password'), confirm);
}

describe('ChangePasswordPage', () => {
  it('sends a signed-out visitor to /login and back afterwards', async () => {
    stubs();
    const router = renderRoute('/settings/password');

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.state).toEqual({ from: '/settings/password' });
  });

  it('changes the password, ends the session and asks to sign in again', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubs();
    const router = renderRoute('/settings/password');

    expect(screen.getByLabelText('Current password')).toHaveAttribute(
      'autocomplete',
      'current-password',
    );
    expect(screen.getByLabelText('New password')).toHaveAttribute('autocomplete', 'new-password');
    await fill(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Password changed. Sign in again.');
    expect(router.state.location.pathname).toBe('/login');
    const [call] = http.callsTo('POST', '/api/v1/auth/change-password');
    expect(JSON.parse(call!.body!)).toEqual({
      currentPassword: CURRENT,
      newPassword: NEW_PASSWORD,
    });
    expect(call!.headers.get('Authorization')).toBe(`Bearer ${CUSTOMER}`);
    expect(getAccessToken()).toBeNull();
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('shows "Current password is incorrect" on 401 and never refreshes', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubs(() => errorResponse(401, 'UNAUTHORIZED', 'Authentication required'));
    const router = renderRoute('/settings/password');

    await fill(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(WRONG_CURRENT_PASSWORD_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(0);
    expect(http.callsTo('POST', '/api/v1/auth/change-password')).toHaveLength(1);
    expect(getAccessToken()).toBe(CUSTOMER);
    expect(router.state.location.pathname).toBe('/settings/password');
    expect(screen.getByLabelText('Current password')).toHaveValue('');
  });

  it('shows a policy refusal from the server', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubs(() =>
      errorResponse(400, 'VALIDATION_ERROR', 'newPassword: must not repeat a recent password'),
    );
    renderRoute('/settings/password');

    await fill(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'New password must not repeat a recent password.',
    );
    expect(getAccessToken()).toBe(CUSTOMER);
  });

  it('checks the fields before sending', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/settings/password');

    await user.type(screen.getByLabelText('New password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    expect(screen.getByLabelText('Current password')).toHaveFocus();
    expect(screen.getByLabelText('Current password')).toHaveAccessibleDescription(
      'Enter your current password.',
    );
    expect(screen.getByLabelText('New password')).toHaveAccessibleDescription(
      /Password must be 12 to 128 characters\./,
    );
    expect(http.callsTo('POST', '/api/v1/auth/change-password')).toHaveLength(0);
  });

  it('sends one request for a double click', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const pending = deferred<Response>();
    const http = stubs(() => pending.promise);
    renderRoute('/settings/password');

    await fill(user);
    await user.dblClick(screen.getByRole('button', { name: 'Change password' }));

    expect(screen.getByRole('button', { name: 'Changing password…' })).toBeDisabled();
    pending.resolve(emptyResponse(200));
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(http.callsTo('POST', '/api/v1/auth/change-password')).toHaveLength(1);
  });

  it('goes to /login with the session-ended notice when the session had already ended', async () => {
    setSession(CUSTOMER, 0); // expired: change-password refreshes first, and the refresh is refused
    const user = userEvent.setup();
    const http = stubs();
    const router = renderRoute('/settings/password');

    await fill(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('status')).toHaveTextContent(SESSION_ENDED_MESSAGE);
    expect(router.state.location.pathname).toBe('/login');
    expect(http.callsTo('POST', '/api/v1/auth/change-password')).toHaveLength(0);
  });
});
