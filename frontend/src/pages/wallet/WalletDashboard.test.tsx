import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { KYC_REQUIRED_MESSAGE } from '../../components/KycRequiredNotice';
import { deferred, errorResponse, jsonResponse, makeJwt, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { ACTIVE_WALLET, FROZEN_WALLET, LIMITS } from '../../test/walletFixtures';
import { FREEZE_WARNING, FROZEN_DONE_MESSAGE } from './WalletDashboard';
import { LIMITS_NOTE } from './WalletLimitsView';
import { FROZEN_EXPLANATION } from './walletText';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const health = () => jsonResponse(200, { status: 'UP' });

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs(routes: Record<string, Handler | Handler[]> = {}) {
  return stubFetch({
    'GET /actuator/health': health,
    'GET /api/v1/wallet': () => jsonResponse(200, ACTIVE_WALLET),
    'GET /api/v1/wallet/limits': () => jsonResponse(200, LIMITS),
    ...routes,
  });
}

function walletRegion() {
  return within(screen.getByRole('region', { name: 'Wallet' }));
}

async function renderLoaded(routes: Record<string, Handler | Handler[]> = {}) {
  setSession(CUSTOMER, 900);
  const http = stubs(routes);
  const router = renderRoute('/');
  await walletRegion().findByText('12345678903');
  return { http, router };
}

describe('WalletDashboard', () => {
  it('shows balance, number, status, tier, Bangkok creation time and the limits', async () => {
    setSession(CUSTOMER, 900);
    stubs();
    renderRoute('/');

    expect(walletRegion().getByRole('status')).toHaveTextContent('Loading your wallet…');
    expect(await walletRegion().findByText('1,500.50 THB')).toBeInTheDocument();
    const wallet = walletRegion();
    expect(wallet.getByText('200.00 THB')).toBeInTheDocument();
    expect(wallet.getByText('12345678903')).toBeInTheDocument();
    expect(wallet.getByText('Active')).toBeInTheDocument();
    expect(wallet.getByText('Basic')).toBeInTheDocument();
    expect(wallet.getByText('19 Nov 2026, 17:30')).toBeInTheDocument();
    expect(wallet.getByText('(Bangkok time)')).toBeInTheDocument();
    expect(wallet.getByText(/Simulated money/)).toBeInTheDocument();
    expect(wallet.getByRole('link', { name: 'Deposit' })).toHaveAttribute('href', '/deposit');
    expect(wallet.getByRole('link', { name: 'Withdraw' })).toHaveAttribute('href', '/withdraw');
    expect(wallet.getByRole('button', { name: 'Freeze wallet' })).toBeInTheDocument();

    const limits = within(await screen.findByRole('region', { name: 'Limits' }));
    const deposit = within(limits.getByRole('row', { name: /Deposit/ }));
    expect(deposit.getByText('100.00 THB')).toBeInTheDocument();
    expect(deposit.getByText('100,000.00 THB')).toBeInTheDocument();
    expect(deposit.getByText('200,000.00 THB')).toBeInTheDocument();
    const withdrawal = within(limits.getByRole('row', { name: /Withdrawal/ }));
    expect(withdrawal.getByText('30,000.00 THB')).toBeInTheDocument();
    expect(withdrawal.getByText('50,000.00 THB')).toBeInTheDocument();
    const transfer = within(limits.getByRole('row', { name: /Transfer/ }));
    expect(transfer.getByText('1.00 THB')).toBeInTheDocument();
    expect(limits.getByText(LIMITS_NOTE)).toBeInTheDocument();
  });

  it('does not ask for limits before KYC and shows the KYC notice on 403 KYC_REQUIRED', async () => {
    setSession(CUSTOMER, 900);
    const http = stubs({ 'GET /api/v1/wallet': () => errorResponse(403, 'KYC_REQUIRED') });
    renderRoute('/');

    expect(await walletRegion().findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
    expect(walletRegion().getByRole('link', { name: 'Go to KYC' })).toHaveAttribute('href', '/kyc');
    expect(http.callsTo('GET', '/api/v1/wallet/limits')).toHaveLength(0);
    expect(screen.queryByRole('region', { name: 'Limits' })).not.toBeInTheDocument();
  });

  it('shows a load error with its reference and loads again on Try again', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubs({
      'GET /api/v1/wallet': [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, ACTIVE_WALLET),
      ],
    });
    renderRoute('/');

    const alert = await walletRegion().findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await walletRegion().findByText('12345678903')).toBeInTheDocument();
  });

  it('shows the wallet even when the limits cannot be loaded', async () => {
    await renderLoaded({ 'GET /api/v1/wallet/limits': () => errorResponse(500, 'SYSTEM_ERROR') });

    const limits = within(await screen.findByRole('region', { name: 'Limits' }));
    expect(limits.getByText(/Your limits could not be loaded\./)).toHaveTextContent(
      'Reference: srv-1',
    );
    expect(walletRegion().getByText('1,500.50 THB')).toBeInTheDocument();
  });

  it('for a frozen wallet hides deposit, withdraw and freeze and says only staff unfreeze', async () => {
    setSession(CUSTOMER, 900);
    stubs({ 'GET /api/v1/wallet': () => jsonResponse(200, FROZEN_WALLET) });
    renderRoute('/');

    expect(await walletRegion().findByText('Frozen')).toBeInTheDocument();
    expect(walletRegion().getByText(FROZEN_EXPLANATION)).toBeInTheDocument();
    expect(walletRegion().queryByRole('link', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(walletRegion().queryByRole('link', { name: 'Withdraw' })).not.toBeInTheDocument();
    expect(walletRegion().queryByRole('button', { name: 'Freeze wallet' })).not.toBeInTheDocument();
  });

  it('refreshes on the button and keeps the data when a refresh fails', async () => {
    const user = userEvent.setup();
    const { http } = await renderLoaded({
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => jsonResponse(200, { ...ACTIVE_WALLET, availableBalance: 99.99 }),
        () => errorResponse(503, 'SERVICE_DEGRADED'),
      ],
    });

    await user.click(walletRegion().getByRole('button', { name: 'Refresh' }));
    expect(await walletRegion().findByText('99.99 THB')).toBeInTheDocument();

    await user.click(walletRegion().getByRole('button', { name: 'Refresh' }));
    expect(
      await walletRegion().findByText(/The wallet could not be refreshed\./),
    ).toHaveTextContent('Reference: srv-1');
    expect(walletRegion().getByText('99.99 THB')).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(3);
  });

  it('refetches when the window regains focus', async () => {
    const { http } = await renderLoaded({
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => jsonResponse(200, FROZEN_WALLET),
      ],
    });

    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    expect(await walletRegion().findByText('Frozen')).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2);
  });

  it('moves to KYC when the wallet disappears on refresh', async () => {
    const user = userEvent.setup();
    await renderLoaded({
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => errorResponse(404, 'WALLET_NOT_FOUND'),
      ],
    });

    await user.click(walletRegion().getByRole('button', { name: 'Refresh' }));
    expect(await walletRegion().findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
  });
});

describe('WalletDashboard freeze', () => {
  it('asks for confirmation; Cancel sends nothing and returns focus to the button', async () => {
    const user = userEvent.setup();
    const { http } = await renderLoaded();

    await user.click(walletRegion().getByRole('button', { name: 'Freeze wallet' }));
    const confirm = within(walletRegion().getByRole('region', { name: 'Freeze your wallet?' }));
    expect(confirm.getByRole('heading', { name: 'Freeze your wallet?' })).toHaveFocus();
    expect(confirm.getByText(FREEZE_WARNING)).toBeInTheDocument();
    expect(FREEZE_WARNING).toMatch(/only our staff can/);

    await user.click(confirm.getByRole('button', { name: 'Cancel' }));
    expect(walletRegion().getByRole('button', { name: 'Freeze wallet' })).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/wallet/freeze')).toHaveLength(0);
  });

  it('closes the confirmation on Escape', async () => {
    const user = userEvent.setup();
    const { http } = await renderLoaded();

    await user.click(walletRegion().getByRole('button', { name: 'Freeze wallet' }));
    await user.keyboard('{Escape}');

    expect(
      walletRegion().queryByRole('region', { name: 'Freeze your wallet?' }),
    ).not.toBeInTheDocument();
    expect(walletRegion().getByRole('button', { name: 'Freeze wallet' })).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/wallet/freeze')).toHaveLength(0);
  });

  it('freezes once on a double click, then shows FROZEN and keeps the session', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const { http, router } = await renderLoaded({
      'POST /api/v1/wallet/freeze': () => answer.promise,
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => jsonResponse(200, FROZEN_WALLET),
      ],
    });

    await user.click(walletRegion().getByRole('button', { name: 'Freeze wallet' }));
    const yes = walletRegion().getByRole('button', { name: 'Yes, freeze my wallet' });
    await user.dblClick(yes);
    expect(walletRegion().getByRole('button', { name: 'Freezing…' })).toBeDisabled();
    answer.resolve(jsonResponse(200, { status: 'FROZEN' }));

    const done = await walletRegion().findByText(FROZEN_DONE_MESSAGE);
    expect(done).toHaveFocus();
    expect(walletRegion().getByText('Frozen')).toBeInTheDocument();
    expect(walletRegion().getByText(FROZEN_EXPLANATION)).toBeInTheDocument();
    expect(walletRegion().queryByRole('link', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(http.callsTo('POST', '/api/v1/wallet/freeze')).toHaveLength(1);
    await waitFor(() => expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(2));
    // Freezing does not end the session.
    expect(router.state.location.pathname).toBe('/');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('shows a freeze error with its reference inside the confirmation', async () => {
    const user = userEvent.setup();
    await renderLoaded({ 'POST /api/v1/wallet/freeze': () => errorResponse(500, 'SYSTEM_ERROR') });

    await user.click(walletRegion().getByRole('button', { name: 'Freeze wallet' }));
    await user.click(walletRegion().getByRole('button', { name: 'Yes, freeze my wallet' }));

    const confirm = within(walletRegion().getByRole('region', { name: 'Freeze your wallet?' }));
    const alert = await confirm.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(confirm.getByRole('button', { name: 'Yes, freeze my wallet' })).toBeEnabled();
  });

  it('reloads into the KYC notice when the freeze finds no wallet', async () => {
    const user = userEvent.setup();
    await renderLoaded({
      'POST /api/v1/wallet/freeze': () => errorResponse(404, 'WALLET_NOT_FOUND'),
      'GET /api/v1/wallet': [
        () => jsonResponse(200, ACTIVE_WALLET),
        () => errorResponse(404, 'WALLET_NOT_FOUND'),
      ],
    });

    await user.click(walletRegion().getByRole('button', { name: 'Freeze wallet' }));
    fireEvent.click(walletRegion().getByRole('button', { name: 'Yes, freeze my wallet' }));

    expect(await walletRegion().findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
  });
});
