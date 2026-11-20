import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { KYC_REQUIRED_MESSAGE } from '../../components/KycRequiredNotice';
import { AMOUNT_MESSAGES } from '../../format/money';
import { deferred, errorResponse, jsonResponse, makeJwt, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import {
  CANCELLED_TRANSFER,
  COMPLETED_TRANSFER,
  EXPIRED_TRANSFER,
  HELD_TRANSFER,
  LOOKUP,
  RECIPIENT_NUMBER,
} from '../../test/transferFixtures';
import { ACTIVE_WALLET, FROZEN_WALLET, LIMITS } from '../../test/walletFixtures';
import { FROZEN_HINT } from '../wallet/MoneyMovementPage';
import { FROZEN_EXPLANATION } from '../wallet/walletText';
import { CANCELLED_MESSAGE } from './CancelTransfer';
import {
  DESCRIPTION_MESSAGES,
  RECIPIENT_NOT_FOUND_MESSAGE,
  WALLET_NUMBER_MESSAGES,
} from './transferText';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const LOOKUP_PATH = '/api/v1/wallets/lookup';
const TRANSFERS = '/api/v1/transfers';

type Handler = Parameters<typeof stubFetch>[0][string];
type User = ReturnType<typeof userEvent.setup>;

function stubs(routes: Record<string, Handler | Handler[]> = {}) {
  return stubFetch({
    'GET /api/v1/wallet': () => jsonResponse(200, ACTIVE_WALLET),
    'GET /api/v1/wallet/limits': () => jsonResponse(200, LIMITS),
    [`GET ${LOOKUP_PATH}`]: () => jsonResponse(200, LOOKUP),
    [`POST ${TRANSFERS}`]: () => jsonResponse(201, COMPLETED_TRANSFER),
    ...routes,
  });
}

async function open(routes: Record<string, Handler | Handler[]> = {}) {
  setSession(CUSTOMER, 900);
  const http = stubs(routes);
  const router = renderRoute('/transfer');
  await screen.findByRole('region', { name: 'Your wallet' });
  return { http, router };
}

async function findRecipient(user: User, number = RECIPIENT_NUMBER) {
  const field = screen.getByLabelText("Recipient's wallet number");
  await user.clear(field);
  await user.type(field, number);
  await user.click(screen.getByRole('button', { name: 'Find recipient' }));
}

async function enterAmount(user: User, amount: string, description?: string) {
  await screen.findByRole('heading', { name: 'Amount' });
  const field = screen.getByLabelText('Amount (THB)');
  await user.clear(field);
  await user.type(field, amount);
  if (description !== undefined) {
    const note = screen.getByLabelText('Description (optional)');
    await user.clear(note);
    await user.type(note, description);
  }
  await user.click(screen.getByRole('button', { name: 'Review' }));
}

async function toConfirm(user: User, amount = '1500', description?: string) {
  await findRecipient(user);
  await enterAmount(user, amount, description);
  return within(screen.getByRole('region', { name: 'Confirm your transfer' }));
}

function keysOf(http: ReturnType<typeof stubs>) {
  return http.callsTo('POST', TRANSFERS).map((call) => call.headers.get('Idempotency-Key'));
}

describe('Transfer page: recipient lookup', () => {
  it('shows the balance, the transfer limits and the simulated-money note', async () => {
    await open();

    const wallet = within(screen.getByRole('region', { name: 'Your wallet' }));
    expect(wallet.getByText('1,500.50 THB')).toBeInTheDocument();
    expect(wallet.getByText('1.00 THB')).toBeInTheDocument();
    expect(wallet.getByText('50,000.00 THB')).toBeInTheDocument();
    expect(wallet.getByText('100,000.00 THB')).toBeInTheDocument();
    expect(screen.getAllByText(/Simulated money/).length).toBeGreaterThan(0);
  });

  it('looks up the number in the query string only and shows the masked name and number', async () => {
    const user = userEvent.setup();
    const { http, router } = await open();

    await findRecipient(user);

    expect(await screen.findByRole('heading', { name: 'Amount' })).toHaveFocus();
    expect(screen.getByText('Somchai J.')).toBeInTheDocument();
    expect(screen.getByText('*******2109')).toBeInTheDocument();
    const [call] = http.callsTo('GET', LOOKUP_PATH);
    expect(call!.search).toBe(`?walletNumber=${RECIPIENT_NUMBER}`);
    // The full wallet number never reaches the SPA's own URL or history state.
    expect(router.state.location.pathname).toBe('/transfer');
    expect(router.state.location.search).toBe('');
    expect(JSON.stringify(router.state.location.state ?? null)).not.toContain(RECIPIENT_NUMBER);
    expect(screen.queryByText(RECIPIENT_NUMBER)).not.toBeInTheDocument();
  });

  it.each([
    ['', WALLET_NUMBER_MESSAGES.empty],
    ['1234567890', WALLET_NUMBER_MESSAGES.format],
    ['1234567890a', WALLET_NUMBER_MESSAGES.format],
  ])('checks the format %j in the browser without a lookup', async (number, message) => {
    const user = userEvent.setup();
    const { http } = await open();

    if (number === '') {
      await user.click(screen.getByRole('button', { name: 'Find recipient' }));
    } else {
      await findRecipient(user, number);
    }

    const field = screen.getByLabelText("Recipient's wallet number");
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAccessibleDescription(expect.stringContaining(message));
    await waitFor(() => expect(field).toHaveFocus());
    expect(http.callsTo('GET', LOOKUP_PATH)).toHaveLength(0);
  });

  it('shows one generic message for the 404, whatever the reason', async () => {
    const user = userEvent.setup();
    await open({ [`GET ${LOOKUP_PATH}`]: () => errorResponse(404, 'NOT_FOUND', 'Not found') });

    await findRecipient(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(RECIPIENT_NOT_FOUND_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).not.toHaveTextContent(/frozen|closed|own|check digit/i);
    expect(screen.getByLabelText("Recipient's wallet number")).toHaveValue(RECIPIENT_NUMBER);
  });

  it('on 429 shows the wait and disables the lookup until Retry-After has passed', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`GET ${LOOKUP_PATH}`]: [
        () =>
          jsonResponse(
            429,
            { success: false, errorCode: 'RATE_LIMITED', message: 'x', correlationId: 'rl-1' },
            { 'Retry-After': '1' },
          ),
        () => jsonResponse(200, LOOKUP),
      ],
    });

    await findRecipient(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many wallet number checks. Please wait 1 second and try again.',
    );
    expect(screen.getByRole('button', { name: 'Wait 1 s' })).toBeDisabled();
    const button = await screen.findByRole('button', { name: 'Find recipient' }, { timeout: 2500 });
    expect(http.callsTo('GET', LOOKUP_PATH)).toHaveLength(1);
    await user.click(button);

    expect(await screen.findByText('Somchai J.')).toBeInTheDocument();
    expect(http.callsTo('GET', LOOKUP_PATH)).toHaveLength(2);
  });

  it('shows the KYC notice when the lookup answers 403 KYC_REQUIRED', async () => {
    const user = userEvent.setup();
    await open({ [`GET ${LOOKUP_PATH}`]: () => errorResponse(403, 'KYC_REQUIRED') });

    await findRecipient(user);

    expect(await screen.findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to KYC' })).toHaveAttribute('href', '/kyc');
  });

  it('on 409 WALLET_FROZEN reloads the wallet and explains the frozen state', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`GET ${LOOKUP_PATH}`]: () => errorResponse(409, 'WALLET_FROZEN'),
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => jsonResponse(200, FROZEN_WALLET),
      ],
    });

    await findRecipient(user);

    expect(await screen.findByText(FROZEN_EXPLANATION)).toBeInTheDocument();
    expect(screen.queryByLabelText("Recipient's wallet number")).not.toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2);
  });

  it('a frozen wallet gets no form, the explanation and a way back', async () => {
    setSession(CUSTOMER, 900);
    const http = stubs({ 'GET /api/v1/wallet': () => jsonResponse(200, FROZEN_WALLET) });
    renderRoute('/transfer');

    expect(await screen.findByText(FROZEN_EXPLANATION)).toBeInTheDocument();
    expect(screen.queryByLabelText("Recipient's wallet number")).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to your wallet' })).toHaveAttribute('href', '/');
    expect(http.callsTo('GET', LOOKUP_PATH)).toHaveLength(0);
  });

  it('before KYC approval shows the KYC notice instead of the form', async () => {
    setSession(CUSTOMER, 900);
    stubs({ 'GET /api/v1/wallet': () => errorResponse(404, 'WALLET_NOT_FOUND') });
    renderRoute('/transfer');

    expect(await screen.findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByLabelText("Recipient's wallet number")).not.toBeInTheDocument();
  });

  it('is for customers only', async () => {
    setSession(makeJwt({ sub: 'staff-1', roles: ['COMPLIANCE_OFFICER'], sid: 's2' }), 900);
    const http = stubFetch({ 'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }) });
    renderRoute('/transfer');

    expect(await screen.findByRole('heading', { level: 1 })).not.toHaveTextContent('Transfer');
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(0);
  });
});

describe('Transfer page: amount and description', () => {
  it('checks the amount format in the browser', async () => {
    const user = userEvent.setup();
    const { http } = await open();

    await findRecipient(user);
    await enterAmount(user, '1.005');

    const field = screen.getByLabelText('Amount (THB)');
    expect(field).toHaveAccessibleDescription(expect.stringContaining(AMOUNT_MESSAGES.decimals));
    await waitFor(() => expect(field).toHaveFocus());
    expect(screen.queryByRole('region', { name: 'Confirm your transfer' })).not.toBeInTheDocument();
    expect(http.callsTo('POST', TRANSFERS)).toHaveLength(0);
  });

  it('refuses a description with an invisible format character', async () => {
    const user = userEvent.setup();
    await open();

    await findRecipient(user);
    await enterAmount(user, '10', 'Rent​share');

    expect(screen.getByLabelText('Description (optional)')).toHaveAccessibleDescription(
      expect.stringContaining(DESCRIPTION_MESSAGES.controlCharacters),
    );
    expect(screen.queryByRole('region', { name: 'Confirm your transfer' })).not.toBeInTheDocument();
  });

  it('"Change recipient" returns to the lookup with the number kept', async () => {
    const user = userEvent.setup();
    await open();

    await findRecipient(user);
    await user.click(await screen.findByRole('button', { name: 'Change recipient' }));

    expect(screen.getByRole('heading', { name: 'Recipient' })).toHaveFocus();
    expect(screen.getByLabelText("Recipient's wallet number")).toHaveValue(RECIPIENT_NUMBER);
  });
});

describe('Transfer page: confirm and result', () => {
  it('confirm shows the masked recipient before anything is sent; COMPLETED shows the result', async () => {
    const user = userEvent.setup();
    const { http } = await open();

    const confirm = await toConfirm(user, '1500');
    expect(confirm.getByRole('heading', { name: 'Confirm your transfer' })).toHaveFocus();
    expect(confirm.getByText('Somchai J.')).toBeInTheDocument();
    expect(confirm.getByText('*******2109')).toBeInTheDocument();
    expect(confirm.getByText('1,500.00 THB')).toBeInTheDocument();
    expect(confirm.queryByText('Description')).not.toBeInTheDocument();
    expect(http.callsTo('POST', TRANSFERS)).toHaveLength(0);

    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    const result = within(await screen.findByRole('region', { name: 'Transfer completed' }));
    expect(result.getByRole('heading', { name: 'Transfer completed' })).toHaveFocus();
    expect(result.getByText(COMPLETED_TRANSFER.reference)).toBeInTheDocument();
    expect(result.getByText('Somchai J. (*******2109)')).toBeInTheDocument();
    expect(result.getByText('−1,500.00 THB')).toBeInTheDocument();
    expect(result.getByText('Completed')).toBeInTheDocument();
    expect(result.getByText('Posted')).toBeInTheDocument();
    expect(result.getByText('20 Nov 2026, 10:00')).toBeInTheDocument();
    expect(result.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
    expect(result.getByRole('link', { name: 'View transfer details' })).toHaveAttribute(
      'href',
      `/transfers/${COMPLETED_TRANSFER.id}`,
    );
    const [call] = http.callsTo('POST', TRANSFERS);
    expect(call!.body).toBe(
      `{"amount":1500.00,"receiverWalletNumber":"${RECIPIENT_NUMBER}","currency":"THB"}`,
    );
    expect(call!.headers.get('Idempotency-Key')).toMatch(UUID_V4);
    await waitFor(() => expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2));
  });

  it('sends the description exactly as typed, leading and trailing spaces kept', async () => {
    const user = userEvent.setup();
    const { http } = await open();

    const confirm = await toConfirm(user, '0000.30', '  Lunch  ');
    expect(confirm.getByText('Description')).toBeInTheDocument();
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    await screen.findByRole('region', { name: 'Transfer completed' });
    expect(http.callsTo('POST', TRANSFERS)[0]!.body).toBe(
      `{"amount":0.30,"receiverWalletNumber":"${RECIPIENT_NUMBER}","currency":"THB","description":"  Lunch  "}`,
    );
  });

  it('renders a ledgerStatus other than POSTED from the response', async () => {
    const user = userEvent.setup();
    await open({
      [`POST ${TRANSFERS}`]: () =>
        jsonResponse(201, { ...COMPLETED_TRANSFER, ledgerStatus: 'PENDING_POST' }),
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    const result = within(await screen.findByRole('region', { name: 'Transfer completed' }));
    expect(result.getByText('Accounting pending')).toBeInTheDocument();
    expect(result.queryByText('Posted')).not.toBeInTheDocument();
  });

  it('PENDING_REVIEW shows the hold, the Bangkok deadline and cancels after confirmation', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: () => jsonResponse(201, HELD_TRANSFER),
      [`POST ${TRANSFERS}/${HELD_TRANSFER.id}/cancel`]: () => jsonResponse(200, CANCELLED_TRANSFER),
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    const result = within(await screen.findByRole('region', { name: 'Transfer held for review' }));
    expect(result.getByText(/held from your available balance/)).toHaveTextContent(
      /at most 24 hours/,
    );
    expect(result.getAllByText('21 Nov 2026, 10:00').length).toBeGreaterThan(0);
    expect(result.getAllByText('(Bangkok time)').length).toBeGreaterThan(0);
    expect(result.getByText('Held for review')).toBeInTheDocument();
    expect(result.getByText('Not posted')).toBeInTheDocument();

    await user.click(result.getByRole('button', { name: 'Cancel transfer' }));
    expect(screen.getByRole('heading', { name: 'Cancel this transfer?' })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: 'Yes, cancel the transfer' }));

    const cancelled = within(await screen.findByRole('region', { name: 'Transfer cancelled' }));
    expect(cancelled.getByText(CANCELLED_MESSAGE)).toHaveFocus();
    expect(cancelled.getByText('Cancelled')).toBeInTheDocument();
    expect(cancelled.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
    const [cancel] = http.callsTo('POST', `${TRANSFERS}/${HELD_TRANSFER.id}/cancel`);
    expect(cancel!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('a cancel that is too late explains it and reloads the transfer', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: () => jsonResponse(201, HELD_TRANSFER),
      [`POST ${TRANSFERS}/${HELD_TRANSFER.id}/cancel`]: () =>
        errorResponse(409, 'INVALID_TRANSFER_STATE'),
      [`GET ${TRANSFERS}/${HELD_TRANSFER.id}`]: () => jsonResponse(200, EXPIRED_TRANSFER),
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    await user.click(screen.getByRole('button', { name: 'Yes, cancel the transfer' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/can no longer be cancelled/);
    expect(await screen.findByText('Expired')).toBeInTheDocument();
    expect(http.callsTo('GET', `${TRANSFERS}/${HELD_TRANSFER.id}`)).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
  });

  it('403 RISK_BLOCKED says only that the transfer was refused, and renews the key', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(403, 'RISK_BLOCKED', 'Score 95 rule NEW_RECIPIENT'),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The transfer was refused.');
    expect(alert).not.toHaveTextContent(/score|rule|risk/i);
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second).toMatch(UUID_V4);
    expect(second).not.toBe(first);
  });

  it('409 SELF_TRANSFER explains it and keeps the key (nothing was recorded)', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(409, 'SELF_TRANSFER'),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'You cannot send money to your own wallet.',
    );
    expect(screen.getByRole('button', { name: 'Change recipient' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second).toBe(first);
  });

  it('a 404 on confirm shows the same generic recipient message and keeps the key', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(404, 'NOT_FOUND'),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(RECIPIENT_NOT_FOUND_MESSAGE);
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second).toBe(first);
  });

  it('a double click sends exactly one request with one key', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const { http } = await open({ [`POST ${TRANSFERS}`]: () => answer.promise });

    const confirm = await toConfirm(user);
    await user.dblClick(confirm.getByRole('button', { name: 'Confirm transfer' }));
    expect(screen.getByRole('button', { name: 'Sending…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled();
    answer.resolve(jsonResponse(201, COMPLETED_TRANSFER));

    await screen.findByRole('region', { name: 'Transfer completed' });
    expect(http.callsTo('POST', TRANSFERS)).toHaveLength(1);
  });

  it('retries after a 5xx and a network error with the same key', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => {
          throw new TypeError('Failed to fetch');
        },
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/on our side/);
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/cannot be reached/);
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));

    await screen.findByRole('region', { name: 'Transfer completed' });
    const keys = keysOf(http);
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
  });

  it('waits for Retry-After on a 429 and retries with the same key', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () =>
          jsonResponse(
            429,
            { success: false, errorCode: 'RATE_LIMITED', message: 'x' },
            { 'Retry-After': '1' },
          ),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Please wait 1 second/);
    expect(screen.getByRole('button', { name: 'Wait 1 s' })).toBeDisabled();
    await user.click(
      await screen.findByRole('button', { name: 'Confirm transfer' }, { timeout: 2500 }),
    );

    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second).toBe(first);
  });

  it.each([
    [409, 'INSUFFICIENT_BALANCE', /^Insufficient balance\./, true],
    [409, 'SINGLE_LIMIT_EXCEEDED', /above the limit for a single transaction/, true],
    [409, 'DAILY_LIMIT_EXCEEDED', /^Daily limit exceeded/, true],
    [409, 'IDEMPOTENCY_KEY_REUSED', /no longer matches your request/, true],
    [400, 'INVALID_AMOUNT', /Check the minimum amount/, false],
    [500, 'SYSTEM_ERROR', /on our side/, false],
  ])('maps %i %s to its message (new key: %s)', async (status, code, message, newKey) => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(status, code, 'server detail'),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).not.toHaveTextContent('server detail');
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second !== first).toBe(newKey);
  });

  it('shows the server validation message naming the field', async () => {
    const user = userEvent.setup();
    await open({
      [`POST ${TRANSFERS}`]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'description: must be at most 140 characters'),
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Description must be at most 140 characters.',
    );
  });

  it('on 409 WALLET_FROZEN blocks confirm, says only staff unfreeze and reloads the wallet', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: () => errorResponse(409, 'WALLET_FROZEN'),
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Wallet frozen. Contact support.');
    expect(alert).toHaveTextContent(FROZEN_HINT);
    expect(screen.getByRole('button', { name: 'Confirm transfer' })).toBeDisabled();
    await waitFor(() => expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2));
  });

  it('shows the KYC notice when the transfer answers 403 KYC_REQUIRED', async () => {
    const user = userEvent.setup();
    await open({ [`POST ${TRANSFERS}`]: () => errorResponse(403, 'KYC_REQUIRED') });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));

    expect(await screen.findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
  });

  it('Edit returns to the amount with the values kept, and the next confirm uses a new key', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user, '1500', 'Rent');
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Edit' }));

    expect(screen.getByRole('heading', { name: 'Amount' })).toHaveFocus();
    expect(screen.getByLabelText('Amount (THB)')).toHaveValue('1500');
    expect(screen.getByLabelText('Description (optional)')).toHaveValue('Rent');
    await enterAmount(user, '20');
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));

    await screen.findByRole('region', { name: 'Transfer completed' });
    const calls = http.callsTo('POST', TRANSFERS);
    const [first, second] = keysOf(http);
    expect(second).toMatch(UUID_V4);
    expect(second).not.toBe(first);
    expect(calls[1]!.body).toContain('"amount":20.00');
  });

  it('changing the recipient from the confirm step renews the key', async () => {
    const user = userEvent.setup();
    const { http } = await open({
      [`POST ${TRANSFERS}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(201, COMPLETED_TRANSFER),
      ],
    });

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Change recipient' }));
    await findRecipient(user, '11111111116');
    await enterAmount(user, '1500');
    await user.click(screen.getByRole('button', { name: 'Confirm transfer' }));

    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second).not.toBe(first);
    expect(http.callsTo('POST', TRANSFERS)[1]!.body).toContain(
      '"receiverWalletNumber":"11111111116"',
    );
  });

  it('"Make another transfer" starts empty and the next confirm uses a new key', async () => {
    const user = userEvent.setup();
    const { http } = await open();

    const confirm = await toConfirm(user);
    await user.click(confirm.getByRole('button', { name: 'Confirm transfer' }));
    await user.click(await screen.findByRole('button', { name: 'Make another transfer' }));

    expect(screen.getByLabelText("Recipient's wallet number")).toHaveValue('');
    const again = await toConfirm(user);
    await user.click(again.getByRole('button', { name: 'Confirm transfer' }));
    await screen.findByRole('region', { name: 'Transfer completed' });
    const [first, second] = keysOf(http);
    expect(second).not.toBe(first);
  });
});
