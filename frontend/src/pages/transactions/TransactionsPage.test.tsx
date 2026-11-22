import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { errorResponse, jsonResponse, makeJwt, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import { page } from '../../test/transferFixtures';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const HISTORY = '/api/v1/transactions';

function item(type: string, amount: number, reference: string, ledgerStatus = 'POSTED') {
  return {
    reference,
    type,
    amount,
    currency: 'THB',
    postedAt: '2026-11-20T03:00:00Z',
    ledgerStatus,
  };
}

const ITEMS = [
  item('DEPOSIT', 1000, 'DEP-20261120-000001'),
  item('WITHDRAWAL', 200, 'WDR-20261120-000001'),
  item('TRANSFER_IN', 250.5, 'TRF-in'),
  item('TRANSFER_OUT', 1500, 'TRF-out', 'PENDING_POST'),
  item('SOMETHING_NEW', 5, 'X-1'),
];

type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes) {
  setSession(CUSTOMER, 900);
  return stubFetch(routes);
}

async function findTable() {
  return within(await screen.findByRole('table', { name: 'Your transactions, newest first' }));
}

describe('Transaction history', () => {
  it('shows money in and money out with a sign and a label, Bangkok time and ledger status', async () => {
    const http = setup({ [`GET ${HISTORY}`]: () => jsonResponse(200, page(ITEMS)) });
    renderRoute('/transactions');

    expect(screen.getByRole('heading', { level: 1, name: 'History' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Loading your history…');
    await findTable();
    const rows = screen.getAllByRole('row');
    expect(rows).toHaveLength(6);
    const deposit = within(rows[1]!);
    expect(deposit.getByText('Deposit')).toBeInTheDocument();
    expect(deposit.getByText('Money in')).toBeInTheDocument();
    expect(deposit.getByText('+1,000.00 THB')).toBeInTheDocument();
    expect(deposit.getByText('DEP-20261120-000001')).toBeInTheDocument();
    expect(deposit.getByText('Posted')).toBeInTheDocument();
    expect(deposit.getByText('20 Nov 2026, 10:00')).toBeInTheDocument();
    expect(deposit.getByText('(Bangkok time)')).toBeInTheDocument();
    const withdrawal = within(rows[2]!);
    expect(withdrawal.getByText('Withdrawal')).toBeInTheDocument();
    expect(withdrawal.getByText('Money out')).toBeInTheDocument();
    expect(withdrawal.getByText('−200.00 THB')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Transfer received')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('+250.50 THB')).toBeInTheDocument();
    expect(within(rows[4]!).getByText('Transfer sent')).toBeInTheDocument();
    expect(within(rows[4]!).getByText('−1,500.00 THB')).toBeInTheDocument();
    // ledgerStatus is rendered from the response, never assumed.
    expect(within(rows[4]!).getByText('Accounting pending')).toBeInTheDocument();
    expect(within(rows[5]!).getByText('SOMETHING_NEW')).toBeInTheDocument();
    expect(within(rows[5]!).getByText('5.00 THB')).toBeInTheDocument();
    expect(http.callsTo('GET', HISTORY)[0]!.search).toBe('?page=0&size=20');
    expect(screen.getAllByText(/Simulated money/).length).toBeGreaterThan(0);
  });

  it('shows "No transactions yet" for an empty page (also before KYC approval)', async () => {
    setup({ [`GET ${HISTORY}`]: () => jsonResponse(200, page([], 0, 0, 0)) });
    renderRoute('/transactions');

    expect(await screen.findByText('No transactions yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'History pages' })).not.toBeInTheDocument();
  });

  it('pages through ?page= and asks the server for the 0-based page', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${HISTORY}`]: [
        () => jsonResponse(200, page([ITEMS[0]], 0, 41, 3)),
        () => jsonResponse(200, page([ITEMS[1]], 1, 41, 3)),
      ],
    });
    const router = renderRoute('/transactions');

    const pager = within(await screen.findByRole('navigation', { name: 'History pages' }));
    expect(pager.getByText('Page 1 of 3')).toBeInTheDocument();
    expect(pager.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    await user.click(pager.getByRole('button', { name: 'Next page' }));

    expect(await screen.findByText('−200.00 THB')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?page=2');
    expect(http.callsTo('GET', HISTORY)[1]!.search).toBe('?page=1&size=20');
    expect(screen.getByRole('heading', { name: 'Transactions, page 2' })).toHaveFocus();
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled();
  });

  it('starts at the page in the URL; past the end it offers the first page', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${HISTORY}`]: [
        () => jsonResponse(200, page([], 6, 3, 1)),
        () => jsonResponse(200, page([ITEMS[0]], 0, 1, 1)),
      ],
    });
    const router = renderRoute('/transactions?page=7');

    expect(await screen.findByText('There are no transactions on this page.')).toBeInTheDocument();
    expect(http.callsTo('GET', HISTORY)[0]!.search).toBe('?page=6&size=20');
    const pager = within(screen.getByRole('navigation', { name: 'History pages' }));
    expect(pager.getByRole('button', { name: 'Next page' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Go to the first page' }));

    expect(await screen.findByText('+1,000.00 THB')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('shows a load error with its reference and a retry', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${HISTORY}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, page(ITEMS)),
      ],
    });
    renderRoute('/transactions');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('+1,000.00 THB')).toBeInTheDocument();
  });

  it('a re-render with the same load error does not take the focus back', async () => {
    setup({ [`GET ${HISTORY}`]: () => errorResponse(500, 'SYSTEM_ERROR') });
    const router = renderRoute('/transactions');

    expect(await screen.findByRole('alert')).toHaveFocus();
    const home = screen.getByRole('link', { name: 'Home' });
    home.focus();
    await act(async () => {
      await router.navigate('/transactions', { state: { rerender: 1 } });
    });

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(home).toHaveFocus();
  });

  it('refreshes on the button and on focus; a failed refresh keeps the rows', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${HISTORY}`]: [
        () => jsonResponse(200, page([ITEMS[0]])),
        () => jsonResponse(200, page([ITEMS[1], ITEMS[0]])),
        () => errorResponse(500, 'SYSTEM_ERROR'),
      ],
    });
    renderRoute('/transactions');

    await findTable();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('−200.00 THB')).toBeInTheDocument();
    act(() => {
      fireEvent.focus(window);
    });

    expect(await screen.findByText(/The history could not be refreshed/)).toBeInTheDocument();
    expect(screen.getByText('+1,000.00 THB')).toBeInTheDocument();
    expect(http.callsTo('GET', HISTORY)).toHaveLength(3);
  });

  it('is for customers only', async () => {
    setSession(makeJwt({ sub: 'staff-1', roles: ['ADMIN'], sid: 's2' }), 900);
    const http = stubFetch({ 'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }) });
    renderRoute('/transactions');

    expect(await screen.findByRole('heading', { level: 1 })).not.toHaveTextContent('History');
    expect(http.callsTo('GET', HISTORY)).toHaveLength(0);
  });
});
