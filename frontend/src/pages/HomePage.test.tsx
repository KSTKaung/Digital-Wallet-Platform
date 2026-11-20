import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { KYC_REQUIRED_MESSAGE } from '../components/KycRequiredNotice';
import { errorResponse, jsonResponse, makeJwt, stubFetch } from '../test/http';
import { renderRoute } from '../test/render';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const ADMIN = makeJwt({ sub: 'staff-1', roles: ['ADMIN'], sid: 's2' });
const health = () => jsonResponse(200, { status: 'UP' });

function walletRegion() {
  return within(screen.getByRole('region', { name: 'Wallet' }));
}

describe('HomePage', () => {
  it('shows the KYC notice with a link to /kyc on 404 WALLET_NOT_FOUND', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubFetch({
      'GET /actuator/health': health,
      'GET /api/v1/wallet': () => errorResponse(404, 'WALLET_NOT_FOUND', 'No wallet'),
      'GET /api/v1/kyc/status': () =>
        jsonResponse(200, {
          status: 'NONE',
          applicationId: null,
          verificationLevel: null,
          walletCreated: false,
        }),
    });
    const router = renderRoute('/');

    expect(walletRegion().getByText('Loading your wallet…')).toBeInTheDocument();
    expect(await walletRegion().findByText(KYC_REQUIRED_MESSAGE)).toBeInTheDocument();
    expect(walletRegion().queryByRole('alert')).not.toBeInTheDocument();
    await user.click(walletRegion().getByRole('link', { name: 'Go to KYC' }));

    expect(router.state.location.pathname).toBe('/kyc');
    expect(await screen.findByRole('heading', { name: 'Apply' })).toBeInTheDocument();
  });

  it('shows the wallet dashboard once the wallet exists', async () => {
    setSession(CUSTOMER, 900);
    stubFetch({
      'GET /actuator/health': health,
      'GET /api/v1/wallet': () =>
        jsonResponse(200, {
          walletNumber: '12345678903',
          status: 'ACTIVE',
          availableBalance: 1500.5,
          currency: 'THB',
        }),
      'GET /api/v1/wallet/limits': () => jsonResponse(200, { currency: 'THB' }),
    });
    renderRoute('/');

    expect(await walletRegion().findByText('12345678903')).toBeInTheDocument();
    expect(walletRegion().getByText('Active')).toBeInTheDocument();
    expect(walletRegion().getByText('1,500.50 THB')).toBeInTheDocument();
    expect(walletRegion().getByText(/Simulated money/)).toBeInTheDocument();
  });

  it('shows other wallet errors with their reference', async () => {
    setSession(CUSTOMER, 900);
    stubFetch({
      'GET /actuator/health': health,
      'GET /api/v1/wallet': () => errorResponse(500, 'SYSTEM_ERROR'),
    });
    renderRoute('/');

    const alert = await walletRegion().findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(walletRegion().queryByText(KYC_REQUIRED_MESSAGE)).not.toBeInTheDocument();
  });

  it('does not ask for a wallet for staff or signed-out visitors', async () => {
    setSession(ADMIN, 900);
    const http = stubFetch({ 'GET /actuator/health': health });
    renderRoute('/');

    expect(await screen.findByText('Gateway is up')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Wallet' })).not.toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(0);
  });
});
