import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { NETWORK_ERROR_MESSAGE } from '../../api/errorMessages';
import { setSession } from '../../auth/tokenStore';
import { KYC_REQUIRED_MESSAGE } from '../../components/KycRequiredNotice';
import { AMOUNT_MESSAGES } from '../../format/money';
import { deferred, errorResponse, jsonResponse, makeJwt, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { ACTIVE_WALLET, FROZEN_WALLET, LIMITS } from '../../test/walletFixtures';
import { FROZEN_HINT, MOVEMENT_UNKNOWN_OUTCOME_MESSAGE } from './MoneyMovementPage';
import { FROZEN_EXPLANATION, NOTE_MESSAGES } from './walletText';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const DEPOSITS = '/api/v1/wallet/deposits';
const WITHDRAWALS = '/api/v1/wallet/withdrawals';

const DEPOSIT_RESULT = {
  id: 'a7d1c0de-0000-4000-8000-000000000001',
  walletNumber: '12345678903',
  amount: 150.5,
  status: 'COMPLETED',
  reference: 'DEP-20261120-000001',
  ledgerStatus: 'POSTED',
};
const WITHDRAWAL_RESULT = { ...DEPOSIT_RESULT, amount: 200, reference: 'WDR-20261120-000001' };

type Handler = Parameters<typeof stubFetch>[0][string];
type User = ReturnType<typeof userEvent.setup>;

function stubs(routes: Record<string, Handler | Handler[]> = {}) {
  return stubFetch({
    'GET /api/v1/wallet': () => jsonResponse(200, ACTIVE_WALLET),
    'GET /api/v1/wallet/limits': () => jsonResponse(200, LIMITS),
    [`POST ${DEPOSITS}`]: () => jsonResponse(201, DEPOSIT_RESULT),
    [`POST ${WITHDRAWALS}`]: () => jsonResponse(201, WITHDRAWAL_RESULT),
    ...routes,
  });
}

async function open(
  path: '/deposit' | '/withdraw',
  routes: Record<string, Handler | Handler[]> = {},
) {
  setSession(CUSTOMER, 900);
  const http = stubs(routes);
  const router = renderRoute(path);
  await screen.findByRole('region', { name: 'Your wallet' });
  return { http, router };
}

async function review(user: User, amount: string, note?: string) {
  const field = screen.getByLabelText('Amount (THB)');
  await user.clear(field);
  await user.type(field, amount);
  if (note !== undefined) {
    await user.type(screen.getByLabelText('Note (optional)'), note);
  }
  await user.click(screen.getByRole('button', { name: 'Review' }));
}

function keysOf(http: ReturnType<typeof stubs>, path: string) {
  return http.callsTo('POST', path).map((call) => call.headers.get('Idempotency-Key'));
}

describe('Deposit page', () => {
  it('shows the balance and the deposit limits as information, with the simulated-money note', async () => {
    setSession(CUSTOMER, 900);
    stubs();
    renderRoute('/deposit');

    expect(screen.getByRole('heading', { level: 1, name: 'Deposit' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Loading your wallet…');
    const wallet = within(await screen.findByRole('region', { name: 'Your wallet' }));
    expect(wallet.getByText('1,500.50 THB')).toBeInTheDocument();
    expect(wallet.getByText('100.00 THB')).toBeInTheDocument();
    expect(wallet.getByText('100,000.00 THB')).toBeInTheDocument();
    expect(wallet.getByText('200,000.00 THB')).toBeInTheDocument();
    expect(screen.getAllByText(/Simulated money/).length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Amount (THB)')).toHaveAccessibleDescription(
      'Thai baht, digits only, with up to 2 decimal places, for example 150.50.',
    );
  });

  it('form → confirm → result: one exact request with a UUID key, showing ledgerStatus', async () => {
    const user = userEvent.setup();
    const { http } = await open('/deposit');

    await review(user, '150.5', 'Rent share');
    const confirm = within(screen.getByRole('region', { name: 'Confirm your deposit' }));
    expect(confirm.getByRole('heading', { name: 'Confirm your deposit' })).toHaveFocus();
    expect(confirm.getByText('150.50 THB')).toBeInTheDocument();
    expect(confirm.getByText('Rent share')).toBeInTheDocument();
    expect(http.callsTo('POST', DEPOSITS)).toHaveLength(0);

    await user.click(confirm.getByRole('button', { name: 'Confirm deposit' }));

    const result = within(await screen.findByRole('region', { name: 'Deposit completed' }));
    expect(result.getByRole('heading', { name: 'Deposit completed' })).toHaveFocus();
    expect(result.getByText('150.50 THB')).toBeInTheDocument();
    expect(result.getByText('DEP-20261120-000001')).toBeInTheDocument();
    expect(result.getByText('Completed')).toBeInTheDocument();
    expect(result.getByText('Posted')).toBeInTheDocument();
    const [call] = http.callsTo('POST', DEPOSITS);
    expect(call!.body).toBe('{"amount":150.50,"currency":"THB","reference":"Rent share"}');
    expect(call!.headers.get('Idempotency-Key')).toMatch(UUID_V4);
    // The balance is reloaded after the movement.
    await waitFor(() => expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2));
  });

  it('sends the amount exactly as checked, with no float rounding and no note when empty', async () => {
    const user = userEvent.setup();
    const { http } = await open('/deposit');

    await review(user, '0000.30');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    await screen.findByRole('region', { name: 'Deposit completed' });
    expect(http.callsTo('POST', DEPOSITS)[0]!.body).toBe('{"amount":0.30,"currency":"THB"}');
  });

  it('renders a ledgerStatus other than POSTED from the response', async () => {
    const user = userEvent.setup();
    await open('/deposit', {
      [`POST ${DEPOSITS}`]: () =>
        jsonResponse(201, { ...DEPOSIT_RESULT, ledgerStatus: 'PENDING_POST' }),
    });

    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    const result = within(await screen.findByRole('region', { name: 'Deposit completed' }));
    expect(result.getByText('Accounting pending')).toBeInTheDocument();
    expect(result.queryByText('Posted')).not.toBeInTheDocument();
  });

  it.each([
    ['', AMOUNT_MESSAGES.empty],
    ['0', AMOUNT_MESSAGES.notPositive],
    ['-1', AMOUNT_MESSAGES.notPositive],
    ['1.005', AMOUNT_MESSAGES.decimals],
    ['abc', AMOUNT_MESSAGES.format],
    ['999999999999999999', AMOUNT_MESSAGES.tooLarge],
  ])('refuses the amount %j in the browser and focuses the field', async (amount, message) => {
    const user = userEvent.setup();
    const { http } = await open('/deposit');

    if (amount === '') {
      await user.click(screen.getByRole('button', { name: 'Review' }));
    } else {
      await review(user, amount);
    }

    const field = screen.getByLabelText('Amount (THB)');
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAccessibleDescription(expect.stringContaining(message));
    await waitFor(() => expect(field).toHaveFocus());
    expect(screen.queryByRole('region', { name: 'Confirm your deposit' })).not.toBeInTheDocument();
    expect(http.callsTo('POST', DEPOSITS)).toHaveLength(0);
  });

  it('refuses a note without a letter or digit', async () => {
    const user = userEvent.setup();
    await open('/deposit');

    await review(user, '150', '***');

    expect(screen.getByLabelText('Note (optional)')).toHaveAccessibleDescription(
      expect.stringContaining(NOTE_MESSAGES.noLetterOrDigit),
    );
    expect(screen.queryByRole('region', { name: 'Confirm your deposit' })).not.toBeInTheDocument();
  });

  it('a double click sends exactly one request with one key', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const { http } = await open('/deposit', { [`POST ${DEPOSITS}`]: () => answer.promise });

    await review(user, '150.50');
    await user.dblClick(screen.getByRole('button', { name: 'Confirm deposit' }));
    expect(screen.getByRole('button', { name: 'Depositing…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled();
    answer.resolve(jsonResponse(201, DEPOSIT_RESULT));

    await screen.findByRole('region', { name: 'Deposit completed' });
    expect(http.callsTo('POST', DEPOSITS)).toHaveLength(1);
  });

  it('retries after a 5xx and after a network error with the same key', async () => {
    const user = userEvent.setup();
    const { http } = await open('/deposit', {
      [`POST ${DEPOSITS}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => {
          throw new TypeError('Failed to fetch');
        },
        () => jsonResponse(201, DEPOSIT_RESULT),
      ],
    });

    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Reference: srv-1');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(NETWORK_ERROR_MESSAGE);
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    await screen.findByRole('region', { name: 'Deposit completed' });
    const keys = keysOf(http, DEPOSITS);
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
  });

  it.each([
    ['a network error', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['500', () => Promise.resolve(errorResponse(500, 'SYSTEM_ERROR'))],
    ['a non-JSON 504', () => Promise.resolve(new Response('Gateway Timeout', { status: 504 }))],
  ])('after %s, Edit and the same draft confirm with the SAME key', async (_name, failure) => {
    const user = userEvent.setup();
    const { http } = await open('/deposit', {
      [`POST ${DEPOSITS}`]: [failure, () => jsonResponse(201, DEPOSIT_RESULT)],
    });

    await review(user, '150.50', 'Rent');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(MOVEMENT_UNKNOWN_OUTCOME_MESSAGE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open your transaction history' })).toHaveAttribute(
      'href',
      '/transactions',
    );
    await user.click(screen.getByRole('button', { name: 'Edit' }));

    expect(screen.getByRole('heading', { name: 'Amount' })).toHaveFocus();
    expect(screen.getByLabelText('Amount (THB)')).toHaveValue('150.50');
    expect(screen.getByLabelText('Note (optional)')).toHaveValue('Rent');
    await user.click(screen.getByRole('button', { name: 'Review' }));
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    await screen.findByRole('region', { name: 'Deposit completed' });
    const [first, second] = keysOf(http, DEPOSITS);
    expect(first).toMatch(UUID_V4);
    expect(second).toBe(first);
  });

  it.each([
    ['the amount', '150.51', 'Rent'],
    ['the note', '150.50', 'Rent2'],
  ])('after an unknown outcome, changing %s sends a new key', async (_name, amount, note) => {
    const user = userEvent.setup();
    const { http } = await open('/deposit', {
      [`POST ${DEPOSITS}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(201, DEPOSIT_RESULT),
      ],
    });

    await review(user, '150.50', 'Rent');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.clear(screen.getByLabelText('Note (optional)'));
    await review(user, amount, note);
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    await screen.findByRole('region', { name: 'Deposit completed' });
    const [first, second] = keysOf(http, DEPOSITS);
    expect(second).toMatch(UUID_V4);
    expect(second).not.toBe(first);
  });

  it('a known refusal (400) shows no unknown-outcome note', async () => {
    const user = userEvent.setup();
    await open('/deposit', {
      [`POST ${DEPOSITS}`]: () => errorResponse(400, 'VALIDATION_ERROR', 'amount is invalid'),
    });

    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    await screen.findByRole('alert');
    expect(screen.queryByText(MOVEMENT_UNKNOWN_OUTCOME_MESSAGE)).not.toBeInTheDocument();
  });

  it('after a success, "Make another deposit" starts an empty form with a new key', async () => {
    const user = userEvent.setup();
    const { http } = await open('/deposit');

    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    await user.click(await screen.findByRole('button', { name: 'Make another deposit' }));

    expect(screen.getByLabelText('Amount (THB)')).toHaveValue('');
    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));
    await screen.findByRole('region', { name: 'Deposit completed' });
    const [first, second] = keysOf(http, DEPOSITS);
    expect(second).not.toBe(first);
  });

  it('shows the KYC notice when the deposit answers 403 KYC_REQUIRED', async () => {
    const user = userEvent.setup();
    await open('/deposit', { [`POST ${DEPOSITS}`]: () => errorResponse(403, 'KYC_REQUIRED') });

    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    expect(await screen.findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to KYC' })).toHaveAttribute('href', '/kyc');
  });

  it('waits for Retry-After on a 429 and then retries with the same key', async () => {
    const user = userEvent.setup();
    const { http } = await open('/deposit', {
      [`POST ${DEPOSITS}`]: [
        () =>
          jsonResponse(
            429,
            { success: false, errorCode: 'RATE_LIMITED', message: 'x', correlationId: 'rl-1' },
            { 'Retry-After': '1' },
          ),
        () => jsonResponse(201, DEPOSIT_RESULT),
      ],
    });

    await review(user, '150.50');
    await user.click(screen.getByRole('button', { name: 'Confirm deposit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Please wait 1 second and try again.',
    );
    expect(screen.getByRole('button', { name: 'Wait 1 s' })).toBeDisabled();
    const button = await screen.findByRole(
      'button',
      { name: 'Confirm deposit' },
      { timeout: 2500 },
    );
    await user.click(button);

    await screen.findByRole('region', { name: 'Deposit completed' });
    const keys = keysOf(http, DEPOSITS);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });
});

describe('Withdraw page', () => {
  it('withdraws through the confirm step and shows the WDR reference and ledger status', async () => {
    const user = userEvent.setup();
    const { http } = await open('/withdraw');

    const wallet = within(screen.getByRole('region', { name: 'Your wallet' }));
    expect(wallet.getByText('30,000.00 THB')).toBeInTheDocument();
    expect(wallet.getByText('50,000.00 THB')).toBeInTheDocument();
    expect(screen.queryByLabelText('Note (optional)')).not.toBeInTheDocument();

    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

    const result = within(await screen.findByRole('region', { name: 'Withdrawal completed' }));
    expect(result.getByText('200.00 THB')).toBeInTheDocument();
    expect(result.getByText('WDR-20261120-000001')).toBeInTheDocument();
    expect(result.getByText('Posted')).toBeInTheDocument();
    const [call] = http.callsTo('POST', WITHDRAWALS);
    expect(call!.body).toBe('{"amount":200.00,"currency":"THB"}');
    expect(call!.headers.get('Idempotency-Key')).toMatch(UUID_V4);
  });

  it('after a stored refusal the next attempt uses a new key', async () => {
    const user = userEvent.setup();
    const { http } = await open('/withdraw', {
      [`POST ${WITHDRAWALS}`]: [
        () => errorResponse(409, 'INSUFFICIENT_BALANCE'),
        () => jsonResponse(201, WITHDRAWAL_RESULT),
      ],
    });

    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/^Insufficient balance/);
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

    await screen.findByRole('region', { name: 'Withdrawal completed' });
    const [first, second] = keysOf(http, WITHDRAWALS);
    expect(second).not.toBe(first);
  });

  it('after a stored refusal, Edit and the same draft use a new key', async () => {
    const user = userEvent.setup();
    const { http } = await open('/withdraw', {
      [`POST ${WITHDRAWALS}`]: [
        () => errorResponse(409, 'INSUFFICIENT_BALANCE'),
        () => jsonResponse(201, WITHDRAWAL_RESULT),
      ],
    });

    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));
    await screen.findByRole('alert');
    expect(screen.queryByText(MOVEMENT_UNKNOWN_OUTCOME_MESSAGE)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

    await screen.findByRole('region', { name: 'Withdrawal completed' });
    const [first, second] = keysOf(http, WITHDRAWALS);
    expect(second).toMatch(UUID_V4);
    expect(second).not.toBe(first);
  });

  it('after a network error, Edit and the same withdrawal confirm with the same key', async () => {
    const user = userEvent.setup();
    const { http } = await open('/withdraw', {
      [`POST ${WITHDRAWALS}`]: [
        () => Promise.reject(new TypeError('Failed to fetch')),
        () => jsonResponse(201, WITHDRAWAL_RESULT),
      ],
    });

    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));
    await screen.findByText(MOVEMENT_UNKNOWN_OUTCOME_MESSAGE);
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

    await screen.findByRole('region', { name: 'Withdrawal completed' });
    const [first, second] = keysOf(http, WITHDRAWALS);
    expect(second).toBe(first);
  });

  it.each([
    [409, 'INSUFFICIENT_BALANCE', /^Insufficient balance\. Deposit or reduce the amount\./, true],
    [409, 'SINGLE_LIMIT_EXCEEDED', /above the limit for a single transaction/, true],
    [409, 'DAILY_LIMIT_EXCEEDED', /^Daily limit exceeded/, true],
    [409, 'IDEMPOTENCY_KEY_REUSED', /no longer matches your request/, true],
    [400, 'INVALID_AMOUNT', /Check the minimum amount/, false],
    [400, 'CURRENCY_MISMATCH', /Only Thai baht \(THB\)/, false],
    [500, 'SYSTEM_ERROR', /on our side/, false],
    [503, 'SERVICE_DEGRADED', /^Service busy, try again\./, false],
  ])(
    'maps %i %s to its message and reference (new key: %s)',
    async (status, code, message, newKey) => {
      const user = userEvent.setup();
      const { http } = await open('/withdraw', {
        [`POST ${WITHDRAWALS}`]: [
          () => errorResponse(status, code, 'server detail'),
          () => jsonResponse(201, WITHDRAWAL_RESULT),
        ],
      });

      await review(user, '200');
      await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(message);
      expect(alert).toHaveTextContent('Reference: srv-1');
      expect(alert).not.toHaveTextContent('server detail');
      await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));
      await screen.findByRole('region', { name: 'Withdrawal completed' });
      const [first, second] = keysOf(http, WITHDRAWALS);
      expect(second !== first).toBe(newKey);
    },
  );

  it('on 409 WALLET_FROZEN says only staff unfreeze, blocks confirm and reloads the wallet', async () => {
    const user = userEvent.setup();
    const { http } = await open('/withdraw', {
      [`POST ${WITHDRAWALS}`]: () => errorResponse(409, 'WALLET_FROZEN'),
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => jsonResponse(200, FROZEN_WALLET),
      ],
    });

    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Wallet frozen. Contact support.');
    expect(alert).toHaveTextContent(FROZEN_HINT);
    expect(screen.getByRole('button', { name: 'Confirm withdrawal' })).toBeDisabled();
    await waitFor(() => expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2));

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.getByText(FROZEN_EXPLANATION)).toBeInTheDocument();
    expect(screen.queryByLabelText('Amount (THB)')).not.toBeInTheDocument();
  });

  it('shows the server validation message naming the field', async () => {
    const user = userEvent.setup();
    await open('/withdraw', {
      [`POST ${WITHDRAWALS}`]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'amount: must not be null'),
    });

    await review(user, '200');
    await user.click(screen.getByRole('button', { name: 'Confirm withdrawal' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Amount must not be null.');
  });

  it('a frozen wallet gets no form, the explanation and a way back', async () => {
    setSession(CUSTOMER, 900);
    const http = stubs({ 'GET /api/v1/wallet': () => jsonResponse(200, FROZEN_WALLET) });
    renderRoute('/withdraw');

    expect(await screen.findByText(FROZEN_EXPLANATION)).toBeInTheDocument();
    expect(screen.queryByLabelText('Amount (THB)')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to your wallet' })).toHaveAttribute('href', '/');
    expect(http.callsTo('POST', WITHDRAWALS)).toHaveLength(0);
  });

  it('before KYC approval shows the KYC notice instead of the form', async () => {
    setSession(CUSTOMER, 900);
    stubs({ 'GET /api/v1/wallet': () => errorResponse(404, 'WALLET_NOT_FOUND') });
    renderRoute('/withdraw');

    expect(await screen.findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByLabelText('Amount (THB)')).not.toBeInTheDocument();
  });

  it('shows a load error with its reference and a retry', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubs({
      'GET /api/v1/wallet': [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, ACTIVE_WALLET),
      ],
    });
    renderRoute('/withdraw');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByLabelText('Amount (THB)')).toBeInTheDocument();
  });

  it('is for customers only', async () => {
    setSession(makeJwt({ sub: 'staff-1', roles: ['ADMIN'], sid: 's2' }), 900);
    const http = stubFetch({ 'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }) });
    renderRoute('/withdraw');

    expect(await screen.findByRole('heading', { level: 1 })).not.toHaveTextContent('Withdraw');
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(0);
  });
});
