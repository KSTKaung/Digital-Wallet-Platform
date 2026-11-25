import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { RESET_EXPIRED_MESSAGE } from '../../api/errorMessages';
import { emptyResponse, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { CODE_SENT_MESSAGE } from './PasswordResetPage';

const NEW_PASSWORD = 'a brand new passphrase';
const TOKEN = 'reset-token-value';

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs({
  forgot = () => emptyResponse(202),
  verify = () => jsonResponse(200, { resetToken: TOKEN }),
  reset = () => emptyResponse(200),
}: { forgot?: Handler; verify?: Handler; reset?: Handler } = {}) {
  return stubFetch({
    'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    'POST /api/v1/auth/forgot-password': forgot,
    'POST /api/v1/auth/verify-otp': verify,
    'POST /api/v1/auth/reset-password': reset,
    'POST /api/v1/auth/resend-otp': () => emptyResponse(202),
  });
}

async function requestCode(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Email'), 'a@example.com');
  await user.click(screen.getByRole('button', { name: 'Send code' }));
  await screen.findByRole('heading', { level: 1, name: 'Enter your code' });
}

async function enterCode(user: ReturnType<typeof userEvent.setup>, code = '654321') {
  await user.type(screen.getByLabelText('Verification code'), code);
  await user.click(screen.getByRole('button', { name: 'Continue' }));
}

async function choosePassword(user: ReturnType<typeof userEvent.setup>, confirm = NEW_PASSWORD) {
  await screen.findByRole('heading', { level: 1, name: 'Choose a new password' });
  await user.type(screen.getByLabelText('New password'), NEW_PASSWORD);
  await user.type(screen.getByLabelText('Confirm new password'), confirm);
  await user.click(screen.getByRole('button', { name: 'Set new password' }));
}

describe('PasswordResetPage from /forgot-password', () => {
  it('resets the password end to end and signs in afresh', async () => {
    const user = userEvent.setup();
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const http = stubs();
    const router = renderRoute('/forgot-password');

    expect(
      screen.getByRole('heading', { level: 1, name: 'Reset your password' }),
    ).toBeInTheDocument();
    await requestCode(user);

    expect(screen.getByRole('heading', { level: 1, name: 'Enter your code' })).toHaveFocus();
    expect(screen.getByText(CODE_SENT_MESSAGE)).toBeInTheDocument();
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/forgot-password')[0]!.body!)).toEqual({
      email: 'a@example.com',
    });
    expect(screen.getByLabelText('Email')).toHaveValue('a@example.com');
    expect(screen.getByRole('button', { name: 'Send a new code' })).toBeDisabled();

    await enterCode(user);
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/verify-otp')[0]!.body!)).toEqual({
      otp: '654321',
      purpose: 'PASSWORD_RESET',
      identifier: 'a@example.com',
    });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Choose a new password' }),
    ).toHaveFocus();
    expect(screen.getByLabelText('New password')).toHaveAttribute('autocomplete', 'new-password');

    await choosePassword(user);

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Password reset. Sign in with your new password.',
    );
    expect(router.state.location.pathname).toBe('/login');
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/reset-password')[0]!.body!)).toEqual({
      resetToken: TOKEN,
      newPassword: NEW_PASSWORD,
    });
    expect(JSON.stringify(router.state.location)).not.toContain(TOKEN);
    expect(setItem).not.toHaveBeenCalled();
  });

  it('shows the wait when the request is rate limited', async () => {
    const user = userEvent.setup();
    stubs({
      forgot: () =>
        jsonResponse(
          429,
          { success: false, errorCode: 'RATE_LIMITED', message: 'Too many requests' },
          { 'Retry-After': '20' },
        ),
    });
    renderRoute('/forgot-password');

    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.click(screen.getByRole('button', { name: 'Send code' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Please wait 20 seconds and try again.',
    );
    expect(
      screen.getByRole('heading', { level: 1, name: 'Reset your password' }),
    ).toBeInTheDocument();
  });

  it('checks the email before sending', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/forgot-password');

    await user.click(screen.getByRole('button', { name: 'Send code' }));

    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/auth/forgot-password')).toHaveLength(0);
  });

  it.each([
    ['INVALID_OTP', /The code is wrong or has expired/],
    ['OTP_EXPIRED', /The code has expired/],
    ['OTP_ATTEMPTS_EXCEEDED', /Too many wrong codes/],
  ])('stays on the code step on %s', async (code, text) => {
    const user = userEvent.setup();
    stubs({ verify: () => errorResponse(400, code) });
    renderRoute('/forgot-password');

    await requestCode(user);
    await enterCode(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    expect(screen.getByRole('heading', { level: 1, name: 'Enter your code' })).toBeInTheDocument();
  });

  it('treats a verify answer without a token as a failure', async () => {
    const user = userEvent.setup();
    stubs({ verify: () => emptyResponse(200) });
    renderRoute('/forgot-password');

    await requestCode(user);
    await enterCode(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(/on our side/);
    expect(screen.getByRole('heading', { level: 1, name: 'Enter your code' })).toBeInTheDocument();
  });

  it('checks length and match of the new password before sending', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/forgot-password');

    await requestCode(user);
    await enterCode(user);
    await choosePassword(user, 'something else entirely');

    expect(screen.getByLabelText('Confirm new password')).toHaveFocus();
    expect(screen.getByLabelText('Confirm new password')).toHaveAccessibleDescription(
      'The passwords do not match.',
    );
    expect(http.callsTo('POST', '/api/v1/auth/reset-password')).toHaveLength(0);
  });

  it('shows a password-policy refusal and keeps the reset going', async () => {
    const user = userEvent.setup();
    stubs({
      reset: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'newPassword: must not repeat a recent password'),
    });
    renderRoute('/forgot-password');

    await requestCode(user);
    await enterCode(user);
    await choosePassword(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('New password must not repeat a recent password.');
    expect(within(alert).queryByRole('button', { name: 'Start again' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set new password' })).toBeInTheDocument();
  });

  it.each([
    [
      'INVALID_RESET_TOKEN',
      () => errorResponse(400, 'INVALID_RESET_TOKEN', 'Invalid or expired reset token'),
    ],
    [
      'INVALID_RESET_TOKEN with any message',
      () => errorResponse(400, 'INVALID_RESET_TOKEN', 'newPassword: unrelated text'),
    ],
    ['a 401', () => errorResponse(401, 'UNAUTHORIZED')],
  ])('asks to start again after %s', async (_name, reset) => {
    const user = userEvent.setup();
    const http = stubs({ reset });
    renderRoute('/forgot-password');

    await requestCode(user);
    await enterCode(user);
    await choosePassword(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(RESET_EXPIRED_MESSAGE);
    expect(screen.queryByRole('button', { name: 'Set new password' })).not.toBeInTheDocument();
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(1);

    await user.click(within(alert).getByRole('button', { name: 'Start again' }));

    expect(screen.getByRole('heading', { level: 1, name: 'Reset your password' })).toHaveFocus();
    expect(screen.getByLabelText('Email')).toHaveValue('a@example.com');
  });

  it.each([
    // The pre-PR 7 answer for a bad token: no longer read as an expired reset (E3).
    ['without a field', 'Invalid or expired reset token', 'Invalid or expired reset token'],
    ['naming another field', 'resetToken: must not be blank', 'resetToken must not be blank.'],
  ])(
    'shows a VALIDATION_ERROR %s as it is and keeps the reset going',
    async (_name, message, shown) => {
      const user = userEvent.setup();
      stubs({ reset: () => errorResponse(400, 'VALIDATION_ERROR', message) });
      renderRoute('/forgot-password');

      await requestCode(user);
      await enterCode(user);
      await choosePassword(user);

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(shown);
      expect(alert).not.toHaveTextContent(RESET_EXPIRED_MESSAGE);
      expect(within(alert).queryByRole('button', { name: 'Start again' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Set new password' })).toBeInTheDocument();
    },
  );
});

describe('PasswordResetPage from /reset-password', () => {
  it('starts at the code step and asks for the email', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/reset-password');

    expect(screen.getByRole('heading', { level: 1, name: 'Enter your code' })).toBeInTheDocument();
    expect(screen.queryByText(CODE_SENT_MESSAGE)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Verification code')).toHaveAttribute(
      'autocomplete',
      'one-time-code',
    );

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/auth/verify-otp')).toHaveLength(0);

    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.click(screen.getByRole('button', { name: 'Send a new code' }));
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/resend-otp')[0]!.body!)).toEqual({
      identifier: 'a@example.com',
      purpose: 'PASSWORD_RESET',
    });
  });

  it('asks for the email before resending', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/reset-password');

    await user.click(screen.getByRole('button', { name: 'Send a new code' }));

    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/auth/resend-otp')).toHaveLength(0);
  });
});
