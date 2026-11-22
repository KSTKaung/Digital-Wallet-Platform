import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { deferred, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import { ADMIN, CUSTOMER_ID } from '../../test/kycReviewFixtures';
import { renderRoute } from '../../test/render';
import { CUSTOMER_WALLET_NOT_FOUND_MESSAGE, resultMessage } from './customerWalletText';

const PAGE = '/admin/customers';
const BASE = `/api/v1/admin/customers/${CUSTOMER_ID}`;
const FREEZE = `POST ${BASE}/freeze`;
const UNFREEZE = `POST ${BASE}/unfreeze`;
const REASON = 'Suspected account takeover';
const health = () => jsonResponse(200, { status: 'UP' });

type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes = {}) {
  setSession(ADMIN, 900);
  const user = userEvent.setup();
  const http = stubFetch({ 'GET /actuator/health': health, ...routes });
  const router = renderRoute(PAGE);
  return { user, http, router };
}

function adminCalls(http: ReturnType<typeof stubFetch>) {
  return http.calls.filter((call) => call.path.startsWith('/api/v1/admin'));
}

async function fill(user: ReturnType<typeof userEvent.setup>, id: string, reason: string) {
  if (id) {
    await user.type(screen.getByLabelText('Customer ID'), id);
  }
  if (reason) {
    await user.type(screen.getByLabelText('Reason'), reason);
  }
}

describe('/admin/customers', () => {
  it('shows the form with labelled fields and both actions, and calls no API', () => {
    const { http } = setup();

    expect(screen.getByRole('heading', { level: 1, name: 'Wallet freeze' })).toBeInTheDocument();
    expect(screen.getByLabelText('Customer ID')).toHaveAttribute('autocomplete', 'off');
    expect(screen.getByLabelText('Customer ID')).toBeRequired();
    expect(screen.getByLabelText('Reason')).toBeRequired();
    expect(screen.getByLabelText('Reason')).toHaveAccessibleDescription(
      /Staff only: the reason is kept in the audit log\. The email to the customer never includes it\./,
    );
    expect(screen.getByText(/cannot show the wallet's current status/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Freeze wallet' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unfreeze wallet' })).toBeInTheDocument();
    expect(adminCalls(http)).toHaveLength(0);
  });

  it('refuses an id that is not a UUID and a missing reason, focusing the first field, without a request', async () => {
    const { user, http } = setup();

    await fill(user, 'not-a-uuid', '');
    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));

    const id = screen.getByLabelText('Customer ID');
    expect(id).toHaveAttribute('aria-invalid', 'true');
    expect(id).toHaveAccessibleDescription(
      expect.stringContaining('Customer ID must be a UUID') as string,
    );
    expect(id).toHaveFocus();
    expect(screen.getByLabelText('Reason')).toHaveAccessibleDescription(
      expect.stringContaining('Enter a reason.') as string,
    );
    expect(screen.queryByRole('heading', { name: 'Freeze this wallet?' })).not.toBeInTheDocument();
    expect(adminCalls(http)).toHaveLength(0);
  });

  it('asks for the id when it is empty and focuses the reason when only the reason is wrong', async () => {
    const { user, http } = setup();

    await user.click(screen.getByRole('button', { name: 'Unfreeze wallet' }));
    expect(screen.getByText('Enter the customer ID.')).toBeInTheDocument();

    await fill(user, CUSTOMER_ID, '!!!');
    await user.click(screen.getByRole('button', { name: 'Unfreeze wallet' }));
    expect(screen.getByLabelText('Customer ID')).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText('Reason')).toHaveFocus();
    expect(screen.getByText('Reason must contain a letter or a digit.')).toBeInTheDocument();
    expect(adminCalls(http)).toHaveLength(0);
  });

  it('refuses a reason over 500 characters or on two lines', async () => {
    const { user, http } = setup();
    await fill(user, CUSTOMER_ID, '');
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'a'.repeat(501) } });

    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));
    expect(screen.getByText('Reason must have at most 500 characters.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'one\ntwo' } });
    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));
    expect(
      screen.getByText('Reason must be on one line, without line breaks.'),
    ).toBeInTheDocument();
    expect(adminCalls(http)).toHaveLength(0);
  });

  it('does nothing on Enter in the id field', async () => {
    const { user, http } = setup();
    await fill(user, CUSTOMER_ID, REASON);

    await user.type(screen.getByLabelText('Customer ID'), '{Enter}');
    expect(screen.queryByRole('heading', { name: /this wallet\?/ })).not.toBeInTheDocument();
    expect(adminCalls(http)).toHaveLength(0);
  });

  it.each([
    [
      'freeze',
      'Freeze wallet',
      'Freeze this wallet?',
      /customer can no longer deposit, withdraw or send money/,
      /already frozen/,
    ],
    [
      'unfreeze',
      'Unfreeze wallet',
      'Unfreeze this wallet?',
      /money can move in and out of it/,
      /already active/,
    ],
  ])(
    '%s: the confirm step names the action and its effect; Cancel and Escape return focus without a request',
    async (_action, button, heading, effect, repeat) => {
      const { user, http } = setup();
      await fill(user, `  ${CUSTOMER_ID.toUpperCase()} `, REASON);

      await user.click(screen.getByRole('button', { name: button }));
      const confirm = screen.getByRole('region', { name: heading });
      expect(screen.getByRole('heading', { name: heading })).toHaveFocus();
      expect(confirm).toHaveTextContent(effect);
      expect(confirm).toHaveTextContent(/the email does not include your reason/);
      expect(confirm).toHaveTextContent(repeat);
      expect(confirm).toHaveTextContent(CUSTOMER_ID);
      expect(confirm).toHaveTextContent(REASON);

      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.getByRole('button', { name: button })).toHaveFocus();
      expect(screen.getByLabelText('Customer ID')).toHaveValue(`  ${CUSTOMER_ID.toUpperCase()} `);
      expect(screen.getByLabelText('Reason')).toHaveValue(REASON);

      await user.click(screen.getByRole('button', { name: button }));
      await user.keyboard('{Escape}');
      expect(screen.queryByRole('heading', { name: heading })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: button })).toHaveFocus();
      expect(adminCalls(http)).toHaveLength(0);
    },
  );

  it.each([
    ['freeze', FREEZE, 'Freeze wallet', 'Yes, freeze the wallet', 'FROZEN'],
    ['unfreeze', UNFREEZE, 'Unfreeze wallet', 'Yes, unfreeze the wallet', 'ACTIVE'],
  ] as const)(
    '%s: sends the trimmed lower-case id once on a double click, then shows the status, clears the reason and keeps the id',
    async (action, route, button, confirmButton, status) => {
      const answer = deferred<Response>();
      const { user, http, router } = setup({ [route]: () => answer.promise });
      await fill(user, ` ${CUSTOMER_ID.toUpperCase()} `, REASON);
      await user.click(screen.getByRole('button', { name: button }));

      await user.dblClick(screen.getByRole('button', { name: confirmButton }));
      const busy = screen.getByRole('button', {
        name: action === 'freeze' ? 'Freezing…' : 'Unfreezing…',
      });
      expect(busy).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
      fireEvent.keyDown(busy, { key: 'Escape' });
      expect(busy).toBeInTheDocument();
      answer.resolve(jsonResponse(200, { status }));

      const notice = await screen.findByText(resultMessage(action, status));
      expect(notice).toHaveAttribute('role', 'status');
      expect(notice).toHaveFocus();
      expect(notice).not.toHaveTextContent(/\bnow\b/);
      const [call, ...more] = adminCalls(http);
      expect(more).toHaveLength(0);
      expect(call!.path).toBe(`${BASE}/${action}`);
      expect(JSON.parse(call!.body!)).toEqual({ reason: REASON });
      expect(call!.headers.has('Idempotency-Key')).toBe(false);
      expect(screen.getByLabelText('Customer ID')).toHaveValue(` ${CUSTOMER_ID.toUpperCase()} `);
      expect(screen.getByLabelText('Reason')).toHaveValue('');
      // Never in the URL or in storage.
      expect(router.state.location.pathname).toBe(PAGE);
      expect(router.state.location.search).toBe('');
      expect(router.state.location.hash).toBe('');
      expect(window.location.href).not.toContain(CUSTOMER_ID);
      expect(JSON.stringify({ ...localStorage })).not.toContain(CUSTOMER_ID);
      expect(JSON.stringify({ ...sessionStorage })).not.toContain(CUSTOMER_ID);
    },
  );

  it('a repeat gets the same 200 and the same wording, which never claims a change', async () => {
    const { user, http } = setup({ [FREEZE]: () => jsonResponse(200, { status: 'FROZEN' }) });
    for (let n = 0; n < 2; n += 1) {
      if (n === 0) {
        await fill(user, CUSTOMER_ID, REASON);
      } else {
        await fill(user, '', 'Second request');
      }
      await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));
      await user.click(screen.getByRole('button', { name: 'Yes, freeze the wallet' }));
      expect(await screen.findByText(resultMessage('freeze', 'FROZEN'))).toHaveFocus();
    }
    expect(http.callsTo('POST', `${BASE}/freeze`)).toHaveLength(2);
    expect(screen.getByRole('status')).toHaveTextContent(
      'If it was already frozen, nothing changed and no email was sent.',
    );
  });

  it('reports an unexpected status as sent', async () => {
    const { user } = setup({ [UNFREEZE]: () => jsonResponse(200, { status: 'FROZEN' }) });
    await fill(user, CUSTOMER_ID, REASON);
    await user.click(screen.getByRole('button', { name: 'Unfreeze wallet' }));
    await user.click(screen.getByRole('button', { name: 'Yes, unfreeze the wallet' }));

    expect(
      await screen.findByText('The server reports the wallet status as FROZEN, not ACTIVE.'),
    ).toBeInTheDocument();
  });

  it('shows one generic 404 text for an unknown customer or one without a wallet, back on the form with both values kept', async () => {
    const { user } = setup({
      [FREEZE]: () => errorResponse(404, 'NOT_FOUND', 'Resource not found'),
    });
    await fill(user, CUSTOMER_ID, REASON);
    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));
    await user.click(screen.getByRole('button', { name: 'Yes, freeze the wallet' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(CUSTOMER_WALLET_NOT_FOUND_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).not.toHaveTextContent('Resource not found');
    expect(alert).toHaveFocus();
    expect(screen.getByLabelText('Customer ID')).toHaveValue(CUSTOMER_ID);
    expect(screen.getByLabelText('Reason')).toHaveValue(REASON);
    expect(screen.queryByRole('heading', { name: 'Freeze this wallet?' })).not.toBeInTheDocument();
  });

  it.each([
    [
      'reason',
      'reason: must contain a letter or a digit',
      'Reason',
      'Reason must contain a letter or a digit.',
    ],
    ['customerId', 'customerId: invalid value', 'Customer ID', 'Customer ID invalid value.'],
  ])(
    'puts a server 400 naming %s on its field, by field name only, and focuses it',
    async (_field, serverMessage, label, shown) => {
      const { user } = setup({
        [UNFREEZE]: () => errorResponse(400, 'VALIDATION_ERROR', serverMessage),
      });
      await fill(user, CUSTOMER_ID, REASON);
      await user.click(screen.getByRole('button', { name: 'Unfreeze wallet' }));
      await user.click(screen.getByRole('button', { name: 'Yes, unfreeze the wallet' }));

      const field = await screen.findByLabelText(label);
      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(field).toHaveAccessibleDescription(expect.stringContaining(shown) as string);
      expect(field).toHaveFocus();
      expect(screen.getByLabelText('Reason')).toHaveValue(REASON);
      expect(screen.getByLabelText('Customer ID')).toHaveValue(CUSTOMER_ID);
    },
  );

  it.each([
    ['403', () => errorResponse(403, 'FORBIDDEN'), 'Your staff role does not allow this action.'],
    [
      '500 (a CLOSED wallet)',
      () => errorResponse(500, 'SYSTEM_ERROR', 'Unexpected error'),
      'Something went wrong on our side. Please try again.',
    ],
    [
      'a network error',
      () => Promise.reject(new TypeError('Failed to fetch')),
      'The service cannot be reached. Check your connection and try again.',
    ],
    ['429', () => errorResponse(429, 'RATE_LIMITED'), 'Too many requests. Please try again later.'],
  ])('keeps the confirm step open on a %s so the admin can retry', async (_name, answer, text) => {
    const { user, http } = setup({
      [FREEZE]: [answer, () => jsonResponse(200, { status: 'FROZEN' })],
    });
    await fill(user, CUSTOMER_ID, REASON);
    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));
    await user.click(screen.getByRole('button', { name: 'Yes, freeze the wallet' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(text);
    expect(alert).toHaveFocus();
    expect(screen.getByRole('heading', { name: 'Freeze this wallet?' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Yes, freeze the wallet' }));
    expect(await screen.findByText(resultMessage('freeze', 'FROZEN'))).toHaveFocus();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(http.callsTo('POST', `${BASE}/freeze`)).toHaveLength(2);
  });

  it('clears the previous result when a new action starts', async () => {
    const { user } = setup({ [FREEZE]: () => jsonResponse(200, { status: 'FROZEN' }) });
    await fill(user, CUSTOMER_ID, REASON);
    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }));
    await user.click(screen.getByRole('button', { name: 'Yes, freeze the wallet' }));
    await screen.findByText(resultMessage('freeze', 'FROZEN'));

    await fill(user, '', 'Cleared after review');
    await user.click(screen.getByRole('button', { name: 'Unfreeze wallet' }));
    expect(screen.queryByText(resultMessage('freeze', 'FROZEN'))).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Unfreeze this wallet?' })).toHaveFocus();
  });
});
