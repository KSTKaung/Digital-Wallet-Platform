import { render, screen, within } from '@testing-library/react';
import { createBrowserRouter, RouterProvider } from 'react-router';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deferred, emptyResponse, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { routes } from '../../routes/router';
import { takeRegistration } from './registrationHandoff';

const PASSWORD = 'correct horse battery';

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs(
  register: Handler = () =>
    jsonResponse(201, { email: 'a@example.com', status: 'PENDING_VERIFICATION' }),
) {
  return stubFetch({
    'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    'POST /api/v1/auth/register': register,
    'POST /api/v1/auth/verify-otp': () => emptyResponse(200),
  });
}

async function fillRegistration(user: ReturnType<typeof userEvent.setup>, confirm = PASSWORD) {
  await user.type(screen.getByLabelText('Email'), 'a@example.com');
  await user.type(screen.getByLabelText('Mobile number'), '+66812345678');
  await user.type(screen.getByLabelText('Password'), PASSWORD);
  await user.type(screen.getByLabelText('Confirm password'), confirm);
}

describe('RegisterPage', () => {
  let setItem: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    setItem = vi.spyOn(Storage.prototype, 'setItem');
  });

  it('labels every field with the right autocomplete', () => {
    stubs();
    renderRoute('/register');

    expect(
      screen.getByRole('heading', { level: 1, name: 'Create an account' }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByLabelText('Mobile number')).toHaveAttribute('autocomplete', 'tel');
    expect(screen.getByLabelText('Mobile number')).toHaveAccessibleDescription(/\+66812345678/);
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByLabelText('Confirm password')).toHaveAttribute(
      'autocomplete',
      'new-password',
    );
  });

  it('registers, then verifies with the password carried only in memory', async () => {
    const user = userEvent.setup();
    const http = stubs();
    const router = renderRoute('/register');
    const seenLocations: string[] = [];
    router.subscribe((state) => seenLocations.push(JSON.stringify(state.location)));

    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Verify your account' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Enter the 6-digit code we sent to a@example.com.'),
    ).toBeInTheDocument();
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/register')[0]!.body!)).toEqual({
      email: 'a@example.com',
      phoneNumber: '+66812345678',
      password: PASSWORD,
    });
    // No router location (URL or state) ever carried the password, not even for a moment.
    expect(seenLocations.length).toBeGreaterThan(0);
    expect(seenLocations.join('|')).not.toContain(PASSWORD);
    expect(router.state.location.pathname).toBe('/verify');
    expect(router.state.location.search).toBe('');
    expect(router.state.location.hash).toBe('');
    expect(router.state.location.state).toBeNull();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Verification code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    const heading = await screen.findByRole('heading', { level: 1, name: 'Account verified' });
    expect(heading).toHaveFocus();
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/verify-otp')[0]!.body!)).toEqual({
      otp: '123456',
      purpose: 'REGISTRATION',
      identifier: 'a@example.com',
      password: PASSWORD,
    });
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/login',
    );
    expect(setItem).not.toHaveBeenCalled();
    expect(takeRegistration()).toBeNull();
  });

  it('never writes the password to window.history with a browser router', async () => {
    const user = userEvent.setup();
    stubs();
    window.history.replaceState(null, '', '/register');
    const pushState = vi.spyOn(History.prototype, 'pushState');
    const replaceState = vi.spyOn(History.prototype, 'replaceState');
    const router = createBrowserRouter(routes);
    render(<RouterProvider router={router} />);

    try {
      await fillRegistration(user);
      await user.click(screen.getByRole('button', { name: 'Create account' }));
      expect(
        await screen.findByRole('heading', { level: 1, name: 'Verify your account' }),
      ).toBeInTheDocument();

      expect(window.location.pathname).toBe('/verify');
      expect(pushState).toHaveBeenCalled();
      const written = [...pushState.mock.calls, ...replaceState.mock.calls].map((args) =>
        JSON.stringify(args),
      );
      expect(written.join('|')).not.toContain(PASSWORD);
      expect(JSON.stringify(window.history.state)).not.toContain(PASSWORD);
      expect(window.location.href).not.toContain(PASSWORD);
    } finally {
      router.dispose();
      window.history.replaceState(null, '', '/');
    }
  });

  it('checks password length and match before sending anything', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/register');

    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.type(screen.getByLabelText('Mobile number'), '+66812345678');
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.type(screen.getByLabelText('Confirm password'), 'different');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    const password = screen.getByLabelText('Password');
    expect(password).toHaveAttribute('aria-invalid', 'true');
    expect(password).toHaveAccessibleDescription(/Password must be 12 to 128 characters\./);
    expect(password).toHaveFocus();
    expect(screen.getByLabelText('Confirm password')).toHaveAccessibleDescription(
      'The passwords do not match.',
    );
    expect(http.callsTo('POST', '/api/v1/auth/register')).toHaveLength(0);
  });

  it('asks for every missing field', async () => {
    const user = userEvent.setup();
    stubs();
    renderRoute('/register');

    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(screen.getByText('Enter your email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your mobile number.')).toBeInTheDocument();
    expect(screen.getByText('Enter a password.')).toBeInTheDocument();
    expect(screen.getByText('Enter the password again.')).toBeInTheDocument();
  });

  it('shows a server validation error with its reference and focuses it', async () => {
    const user = userEvent.setup();
    stubs(() => errorResponse(400, 'VALIDATION_ERROR', 'phoneNumber: must match "\\+[0-9]{8,15}"'));
    const router = renderRoute('/register');

    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Mobile number is not in the expected format.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
    expect(router.state.location.pathname).toBe('/register');
  });

  it('shows the wait time on 429', async () => {
    const user = userEvent.setup();
    stubs(() =>
      jsonResponse(
        429,
        { success: false, errorCode: 'RATE_LIMITED', message: 'Too many requests' },
        { 'Retry-After': '42' },
      ),
    );
    renderRoute('/register');

    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Please wait 42 seconds and try again.',
    );
  });

  it('sends one request for a double click and shows the busy state', async () => {
    const user = userEvent.setup();
    const pending = deferred<Response>();
    const http = stubs(() => pending.promise);
    renderRoute('/register');

    await fillRegistration(user);
    const button = screen.getByRole('button', { name: 'Create account' });
    await user.dblClick(button);

    expect(screen.getByRole('button', { name: 'Creating account…' })).toBeDisabled();
    expect(http.callsTo('POST', '/api/v1/auth/register')).toHaveLength(1);
    pending.resolve(jsonResponse(201, { email: 'a@example.com', status: 'PENDING_VERIFICATION' }));
    expect(await screen.findByRole('heading', { name: 'Verify your account' })).toBeInTheDocument();
    expect(http.callsTo('POST', '/api/v1/auth/register')).toHaveLength(1);
  });

  it('shows the network message when the service cannot be reached', async () => {
    const user = userEvent.setup();
    stubs(() => {
      throw new TypeError('Failed to fetch');
    });
    renderRoute('/register');

    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/cannot be reached/);
  });

  it('toggles password visibility', async () => {
    const user = userEvent.setup();
    stubs();
    renderRoute('/register');
    const input = screen.getByLabelText('Password');

    expect(input).toHaveAttribute('type', 'password');
    const toggle = screen.getByRole('button', { name: 'Show password' });
    expect(toggle).toHaveAttribute('aria-controls', input.id);
    await user.click(toggle);

    expect(input).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(input).toHaveAttribute('type', 'password');
    expect(screen.getByLabelText('Confirm password')).toHaveAttribute('type', 'password');
  });
});
