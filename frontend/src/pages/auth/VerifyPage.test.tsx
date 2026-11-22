import { act, render, screen } from '@testing-library/react';
import { StrictMode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { emptyResponse, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { routes } from '../../routes/router';
import { putRegistration, takeRegistration } from './registrationHandoff';

const PASSWORD = 'correct horse battery';

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs(
  verify: Handler = () => emptyResponse(200),
  resend: Handler = () => emptyResponse(202),
) {
  return stubFetch({
    'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    'POST /api/v1/auth/verify-otp': verify,
    'POST /api/v1/auth/resend-otp': resend,
  });
}

const rateLimited = (seconds: string) =>
  jsonResponse(
    429,
    {
      success: false,
      errorCode: 'RATE_LIMITED',
      message: 'Too many requests',
      correlationId: 'rl-1',
    },
    { 'Retry-After': seconds },
  );

async function fillManual(user: ReturnType<typeof userEvent.setup>, otp = '123456') {
  await user.type(screen.getByLabelText('Email'), 'a@example.com');
  await user.type(screen.getByLabelText('Password'), PASSWORD);
  await user.type(screen.getByLabelText('Verification code'), otp);
}

describe('VerifyPage opened directly', () => {
  it('asks for email, password and the code, with the right input hints', () => {
    stubs();
    renderRoute('/verify');

    expect(
      screen.getByRole('heading', { level: 1, name: 'Verify your account' }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    const otp = screen.getByLabelText('Verification code');
    expect(otp).toHaveAttribute('autocomplete', 'one-time-code');
    expect(otp).toHaveAttribute('inputmode', 'numeric');
    expect(otp).toHaveAttribute('maxlength', '6');
    expect(otp).toHaveAccessibleDescription(/6-digit code/);
    expect(screen.getByRole('button', { name: 'Send a new code' })).toBeEnabled();
  });

  it('verifies with the entered password and clears the form', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/verify');

    await fillManual(user);
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByRole('heading', { name: 'Account verified' })).toHaveFocus();
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/verify-otp')[0]!.body!)).toEqual({
      otp: '123456',
      purpose: 'REGISTRATION',
      identifier: 'a@example.com',
      password: PASSWORD,
    });
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('checks the code format before sending', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/verify');

    await fillManual(user, '12a45');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    expect(screen.getByLabelText('Verification code')).toHaveFocus();
    expect(screen.getByLabelText('Verification code')).toHaveAccessibleDescription(
      /The code is 6 digits\./,
    );
    expect(http.callsTo('POST', '/api/v1/auth/verify-otp')).toHaveLength(0);
  });

  it.each([
    ['INVALID_OTP', /The code is wrong or has expired/],
    ['OTP_EXPIRED', /The code has expired\. Request a new code\./],
    ['OTP_ATTEMPTS_EXCEEDED', /Too many wrong codes\. Request a new code\./],
  ])('shows %s with its reference and focuses the message', async (code, text) => {
    const user = userEvent.setup();
    stubs(() => errorResponse(400, code, 'Invalid or expired code'));
    renderRoute('/verify');

    await fillManual(user);
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(text);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
  });

  it('shows the wait on 429', async () => {
    const user = userEvent.setup();
    stubs(() => rateLimited('17'));
    renderRoute('/verify');

    await fillManual(user);
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Please wait 17 seconds and try again.',
    );
  });

  it('resends a code with a neutral confirmation and restarts the countdown', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/verify');

    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.click(screen.getByRole('button', { name: 'Send a new code' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      /If this email is waiting for a code, we have sent a new one/,
    );
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/resend-otp')[0]!.body!)).toEqual({
      identifier: 'a@example.com',
      purpose: 'REGISTRATION',
    });
    const button = screen.getByRole('button', { name: 'Send a new code' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription('You can request a new code in 60 seconds.');
  });

  it('asks for the email before resending', async () => {
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/verify');

    await user.click(screen.getByRole('button', { name: 'Send a new code' }));

    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('Enter your email address.');
    expect(http.callsTo('POST', '/api/v1/auth/resend-otp')).toHaveLength(0);
  });

  it('shows the Retry-After wait when resend is rate limited and waits that long', async () => {
    const user = userEvent.setup();
    stubs(undefined, () => rateLimited('30'));
    renderRoute('/verify');

    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.click(screen.getByRole('button', { name: 'Send a new code' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Please wait 30 seconds and try again.',
    );
    expect(screen.getByRole('button', { name: 'Send a new code' })).toHaveAccessibleDescription(
      'You can request a new code in 30 seconds.',
    );
  });
});

describe('VerifyPage after registration', () => {
  it('asks only for the code and offers resend after 60 seconds', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    stubs();
    putRegistration({ email: 'a@example.com', password: PASSWORD });
    const router = renderRoute('/verify');

    expect(
      screen.getByText('Enter the 6-digit code we sent to a@example.com.'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
    expect(router.state.location.state).toBeNull();

    const button = screen.getByRole('button', { name: 'Send a new code' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription('You can request a new code in 60 seconds.');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(button).toHaveAccessibleDescription('You can request a new code in 59 seconds.');

    for (let i = 0; i < 58; i += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
      });
    }
    expect(button).toHaveAccessibleDescription('You can request a new code in 1 second.');
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(button).toBeEnabled();
    expect(button).not.toHaveAccessibleDescription();
  });

  it('never prefills from router state, so a crafted history entry cannot supply a password', () => {
    stubs();
    renderRoute('/verify', { email: 'a@example.com', password: PASSWORD });

    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('takes the hand-off once: a second visit (like a reload) shows the full form', async () => {
    const user = userEvent.setup();
    stubs();
    putRegistration({ email: 'a@example.com', password: PASSWORD });
    const router = renderRoute('/verify');
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
    expect(takeRegistration()).toBeNull();

    await act(() => router.navigate('/login'));
    await act(() => router.navigate('/verify'));

    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByLabelText('Verification code')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    expect(screen.getByLabelText('Email')).toHaveFocus();
  });

  it('keeps the hand-off under React StrictMode (double render and effects)', async () => {
    const user = userEvent.setup();
    const http = stubs();
    putRegistration({ email: 'a@example.com', password: PASSWORD });
    const router = createMemoryRouter(routes, { initialEntries: ['/verify'] });
    render(
      <StrictMode>
        <RouterProvider router={router} />
      </StrictMode>,
    );

    expect(
      screen.getByText('Enter the 6-digit code we sent to a@example.com.'),
    ).toBeInTheDocument();
    await user.type(screen.getByLabelText('Verification code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByRole('heading', { name: 'Account verified' })).toBeInTheDocument();
    expect(JSON.parse(http.callsTo('POST', '/api/v1/auth/verify-otp')[0]!.body!)).toMatchObject({
      identifier: 'a@example.com',
      password: PASSWORD,
    });
    expect(takeRegistration()).toBeNull();
  });
});
