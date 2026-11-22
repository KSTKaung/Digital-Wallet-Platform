import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { errorResponse, jsonResponse, stubFetch, tokenResponse } from '../../test/http';
import {
  ADMIN,
  APPLICATION_ID,
  COMPLIANCE,
  CUSTOMER,
  PENDING_SUMMARY,
} from '../../test/kycReviewFixtures';
import { renderRoute } from '../../test/render';
import { page } from '../../test/transferFixtures';
import { PENDING_ITEM, TRANSFER_ID } from '../../test/transferReviewFixtures';
import { NO_STAFF_TOOLS_MESSAGE } from './staffNav';

const LIST = '/api/v1/admin/kyc/applications';
const TRANSFER_LIST = '/api/v1/admin/transfer-reviews';
const TRANSFER_PAGES = ['/admin/transfer-reviews', `/admin/transfer-reviews/${TRANSFER_ID}`];
const health = () => jsonResponse(200, { status: 'UP' });

function nav() {
  return within(screen.getByRole('navigation', { name: 'Main' }));
}

function stubs(extra: Parameters<typeof stubFetch>[0] = {}) {
  return stubFetch({
    'GET /actuator/health': health,
    'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    [`GET ${LIST}`]: () => jsonResponse(200, page([PENDING_SUMMARY])),
    [`GET ${TRANSFER_LIST}`]: () => jsonResponse(200, page([PENDING_ITEM])),
    ...extra,
  });
}

function adminCalls(http: ReturnType<typeof stubFetch>) {
  return http.calls.filter((call) => call.path.startsWith('/api/v1/admin'));
}

describe('staff navigation by role', () => {
  it('shows KYC and transfer reviews to a compliance officer, in the navigation and on the home page', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubs();
    renderRoute('/');

    expect(nav().getByRole('link', { name: 'KYC reviews' })).toHaveAttribute('href', '/admin/kyc');
    expect(nav().queryByRole('link', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'KYC' })).not.toBeInTheDocument();
    const area = within(screen.getByRole('region', { name: 'Staff area' }));
    expect(area.getByRole('link', { name: 'KYC reviews' })).toHaveAttribute('href', '/admin/kyc');
    expect(area.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'KYC reviews',
      'Transfer reviews',
    ]);
    expect(nav().getByRole('link', { name: 'Transfer reviews' })).toHaveAttribute(
      'href',
      '/admin/transfer-reviews',
    );
    expect(await screen.findByText('Gateway is up')).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/wallet')).toHaveLength(0);
  });

  it('shows no KYC reviews to an admin, and says there are no tools yet', async () => {
    setSession(ADMIN, 900);
    stubs();
    renderRoute('/');

    expect(nav().queryByRole('link', { name: 'KYC reviews' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Transfer reviews' })).not.toBeInTheDocument();
    expect(nav().getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    const area = within(screen.getByRole('region', { name: 'Staff area' }));
    expect(area.getByText(NO_STAFF_TOOLS_MESSAGE)).toBeInTheDocument();
    expect(area.queryByRole('link')).not.toBeInTheDocument();
    expect(await screen.findByText('Gateway is up')).toBeInTheDocument();
  });

  it('shows no staff navigation to a customer', async () => {
    setSession(CUSTOMER, 900);
    stubs({ 'GET /api/v1/wallet': () => errorResponse(404, 'WALLET_NOT_FOUND') });
    renderRoute('/');

    expect(nav().getByRole('link', { name: 'Deposit' })).toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'KYC reviews' })).not.toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Transfer reviews' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Staff area' })).not.toBeInTheDocument();
    expect(await screen.findByText('Complete KYC to continue.')).toBeInTheDocument();
  });
});

describe('/admin', () => {
  it('takes a compliance officer to the KYC queue', async () => {
    setSession(COMPLIANCE, 900);
    const http = stubs();
    const router = renderRoute('/admin');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'KYC reviews' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/kyc');
    expect(nav().getByRole('link', { name: 'KYC reviews' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(await screen.findByText('Somchai J.')).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)).toHaveLength(1);
    expect(http.callsTo('GET', TRANSFER_LIST)).toHaveLength(0);
  });

  it('opens the transfer review queue from the navigation', async () => {
    const user = userEvent.setup();
    setSession(COMPLIANCE, 900);
    const http = stubs();
    const router = renderRoute('/admin');
    await screen.findByText('Somchai J.');

    await user.click(nav().getByRole('link', { name: 'Transfer reviews' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Transfer reviews' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/transfer-reviews');
    expect(nav().getByRole('link', { name: 'Transfer reviews' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(nav().getByRole('link', { name: 'KYC reviews' })).not.toHaveAttribute('aria-current');
    expect(await screen.findByText('TRF-20261121-0001')).toBeInTheDocument();
    expect(http.callsTo('GET', TRANSFER_LIST)).toHaveLength(1);
  });

  it('tells an admin there are no staff tools yet, without calling the API', () => {
    setSession(ADMIN, 900);
    const http = stubs();
    const router = renderRoute('/admin');

    expect(screen.getByRole('heading', { level: 1, name: 'Staff area' })).toBeInTheDocument();
    expect(screen.getByText(NO_STAFF_TOOLS_MESSAGE)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin');
    expect(adminCalls(http)).toHaveLength(0);
  });

  it.each([['/admin/kyc'], [`/admin/kyc/${APPLICATION_ID}`], ...TRANSFER_PAGES.map((p) => [p])])(
    'refuses %s to an admin without calling the API',
    (path) => {
      setSession(ADMIN, 900);
      const http = stubs();
      renderRoute(path);

      expect(screen.getByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
      expect(adminCalls(http)).toHaveLength(0);
    },
  );

  it.each([
    ['/admin'],
    ['/admin/kyc'],
    [`/admin/kyc/${APPLICATION_ID}`],
    ['/ADMIN/KYC'],
    ...TRANSFER_PAGES.map((p) => [p]),
  ])('shows a customer at %s "Not available" and calls no admin API', (path) => {
    setSession(CUSTOMER, 900);
    const http = stubs();
    renderRoute(path);

    expect(screen.getByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'KYC reviews' })).not.toBeInTheDocument();
    expect(adminCalls(http)).toHaveLength(0);
  });

  it.each([
    ['/admin'],
    ['/admin/kyc'],
    [`/admin/kyc/${APPLICATION_ID}`],
    ...TRANSFER_PAGES.map((p) => [p]),
  ])('sends a signed-out visitor of %s to sign in, without calling the admin API', async (path) => {
    const http = stubs();
    const router = renderRoute(path);

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(adminCalls(http)).toHaveLength(0);
  });

  it('returns a compliance officer to the queue after signing in', async () => {
    const user = userEvent.setup();
    stubs({ 'POST /api/v1/auth/login': () => tokenResponse(COMPLIANCE) });
    const router = renderRoute('/admin/kyc');

    await user.type(await screen.findByLabelText('Email'), 'officer@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Somchai J.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/kyc');
  });

  it.each([['/deposit'], ['/transfer'], ['/kyc'], ['/transactions']])(
    'keeps staff out of the customer page %s',
    (path) => {
      setSession(COMPLIANCE, 900);
      const http = stubs();
      renderRoute(path);

      expect(screen.getByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
      expect(http.calls.filter((call) => call.path.startsWith('/api/v1/'))).toHaveLength(0);
    },
  );

  it('shows the not-found page for an unknown staff path', () => {
    setSession(COMPLIANCE, 900);
    stubs();
    renderRoute('/admin/nothing-here');

    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
  });
});
