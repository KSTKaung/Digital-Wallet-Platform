import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { deferred, errorResponse, jsonResponse, makeJwt, stubFetch } from '../../test/http';
import { renderRoute } from '../../test/render';
import {
  CANCELLED_TRANSFER,
  COMPLETED_TRANSFER,
  EXPIRED_TRANSFER,
  HELD_TRANSFER,
  page,
  RECEIVED_TRANSFER,
} from '../../test/transferFixtures';
import { CANCEL_WARNING, CANCELLED_MESSAGE } from './CancelTransfer';
import { TRANSFER_NOT_FOUND_MESSAGE } from './transferText';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const LIST = '/api/v1/transfers';
const DETAIL = `/api/v1/transfers/${HELD_TRANSFER.id}`;
const CANCEL = `${DETAIL}/cancel`;

type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes) {
  setSession(CUSTOMER, 900);
  return stubFetch(routes);
}

function table() {
  return within(screen.getByRole('table', { name: 'Your transfers, newest first' }));
}

async function findTable() {
  return within(await screen.findByRole('table', { name: 'Your transfers, newest first' }));
}

describe('Transfers list', () => {
  it('shows direction, masked counterparty, signed amount, status, Bangkok time and ledger', async () => {
    const http = setup({
      [`GET ${LIST}`]: () =>
        jsonResponse(
          200,
          page([
            COMPLETED_TRANSFER,
            RECEIVED_TRANSFER,
            HELD_TRANSFER,
            {
              ...HELD_TRANSFER,
              id: 'r1',
              status: 'REJECTED',
              rejectionReason: 'REVIEW_DECLINED',
              reviewExpiresAt: null,
            },
            {
              ...HELD_TRANSFER,
              id: 'r2',
              status: 'REJECTED',
              rejectionReason: 'WALLET_UNAVAILABLE',
              reviewExpiresAt: null,
            },
            { ...CANCELLED_TRANSFER, id: 'c1' },
            { ...EXPIRED_TRANSFER, id: 'e1' },
            { ...HELD_TRANSFER, id: 'b1', status: 'BLOCKED', reviewExpiresAt: null },
          ]),
        ),
    });
    renderRoute('/transfers');

    expect(screen.getByRole('status')).toHaveTextContent('Loading your transfers…');
    const rows = await screen.findAllByRole('row');
    expect(rows).toHaveLength(9);
    const sent = within(rows[1]!);
    expect(sent.getByText('Sent')).toBeInTheDocument();
    expect(sent.getByText('Somchai J. (*******2109)')).toBeInTheDocument();
    expect(sent.getByText('−1,500.00 THB')).toBeInTheDocument();
    expect(sent.getByText('Completed')).toBeInTheDocument();
    expect(sent.getByText('Posted')).toBeInTheDocument();
    expect(sent.getByText('20 Nov 2026, 10:00')).toBeInTheDocument();
    expect(
      sent.getByRole('link', { name: `Details of transfer ${COMPLETED_TRANSFER.reference}` }),
    ).toHaveAttribute('href', `/transfers/${COMPLETED_TRANSFER.id}`);
    const received = within(rows[2]!);
    expect(received.getByText('Received')).toBeInTheDocument();
    expect(received.getByText('Malee S. (*******5555)')).toBeInTheDocument();
    expect(received.getByText('+250.50 THB')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Held for review')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Not posted')).toBeInTheDocument();
    expect(within(rows[4]!).getByText('Rejected')).toBeInTheDocument();
    expect(within(rows[4]!).getByText('Declined after review.')).toBeInTheDocument();
    expect(within(rows[5]!).getByText(/a wallet could not take part/)).toBeInTheDocument();
    expect(within(rows[6]!).getByText('Cancelled')).toBeInTheDocument();
    expect(within(rows[7]!).getByText('Expired')).toBeInTheDocument();
    expect(within(rows[8]!).getByText('Refused')).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)[0]!.search).toBe('?page=0&size=20');
    // One page only: no pager.
    expect(screen.queryByRole('navigation', { name: 'Transfer pages' })).not.toBeInTheDocument();
  });

  it('shows the empty state', async () => {
    setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([], 0, 0, 0)) });
    renderRoute('/transfers');

    expect(await screen.findByText('No transfers yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Make a transfer' })).toHaveAttribute(
      'href',
      '/transfer',
    );
  });

  it('pages forward and back through ?page= and asks the server for the 0-based page', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([COMPLETED_TRANSFER], 0, 21, 2)),
        () => jsonResponse(200, page([RECEIVED_TRANSFER], 1, 21, 2)),
        () => jsonResponse(200, page([COMPLETED_TRANSFER], 0, 21, 2)),
      ],
    });
    const router = renderRoute('/transfers');

    const pager = within(await screen.findByRole('navigation', { name: 'Transfer pages' }));
    expect(pager.getByText('Page 1 of 2')).toBeInTheDocument();
    expect(pager.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    await user.click(pager.getByRole('button', { name: 'Next page' }));

    expect(await screen.findByText('+250.50 THB')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?page=2');
    expect(http.callsTo('GET', LIST)[1]!.search).toBe('?page=1&size=20');
    expect(screen.getByRole('heading', { name: 'Your transfers, page 2' })).toHaveFocus();
    const second = within(screen.getByRole('navigation', { name: 'Transfer pages' }));
    expect(second.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(second.getByRole('button', { name: 'Next page' })).toBeDisabled();
    await user.click(second.getByRole('button', { name: 'Previous page' }));

    expect(await screen.findByText('−1,500.00 THB')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
    expect(http.callsTo('GET', LIST)[2]!.search).toBe('?page=0&size=20');
  });

  it.each([
    ['/transfers?page=3', '?page=2&size=20'],
    ['/transfers?page=abc', '?page=0&size=20'],
    ['/transfers?page=0', '?page=0&size=20'],
    ['/transfers?page=-4', '?page=0&size=20'],
  ])('reads %s as the page to load', async (path, query) => {
    const http = setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([COMPLETED_TRANSFER])) });
    renderRoute(path);

    await (await findTable()).findByText('Completed');
    expect(http.callsTo('GET', LIST)).toHaveLength(1);
    expect(http.callsTo('GET', LIST)[0]!.search).toBe(query);
  });

  it('a page past the end offers the first page', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([], 4, 3, 1)),
        () => jsonResponse(200, page([COMPLETED_TRANSFER], 0, 1, 1)),
      ],
    });
    const router = renderRoute('/transfers?page=5');

    expect(await screen.findByText('There are no transfers on this page.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Go to the first page' }));
    expect(await screen.findByText('−1,500.00 THB')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('shows a load error with its reference and loads again on Try again', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${LIST}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, page([COMPLETED_TRANSFER])),
      ],
    });
    renderRoute('/transfers');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await (await findTable()).findByText('Completed')).toBeInTheDocument();
  });

  it('a re-render with the same load error does not take the focus back', async () => {
    setup({ [`GET ${LIST}`]: () => errorResponse(500, 'SYSTEM_ERROR') });
    const router = renderRoute('/transfers');

    expect(await screen.findByRole('alert')).toHaveFocus();
    const home = screen.getByRole('link', { name: 'Home' });
    home.focus();
    await act(async () => {
      await router.navigate('/transfers', { state: { rerender: 1 } });
    });

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(home).toHaveFocus();
  });

  it('refreshes on the button and on window focus, keeping the data when a refresh fails', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([HELD_TRANSFER])),
        () => jsonResponse(200, page([{ ...HELD_TRANSFER, status: 'COMPLETED' }])),
        () => errorResponse(503, 'SERVICE_DEGRADED'),
      ],
    });
    renderRoute('/transfers');

    expect(await (await findTable()).findByText('Held for review')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await (await findTable()).findByText('Completed')).toBeInTheDocument();

    act(() => {
      fireEvent.focus(window);
    });
    expect(await screen.findByText(/The transfers could not be refreshed/)).toHaveTextContent(
      'Reference: srv-1',
    );
    expect(table().getByText('Completed')).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)).toHaveLength(3);
  });
});

describe('Transfer detail', () => {
  it('shows every field of a held transfer, with the deadline in Bangkok time', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, HELD_TRANSFER) });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    expect(screen.getByRole('status')).toHaveTextContent('Loading the transfer…');
    expect(await screen.findByText(HELD_TRANSFER.reference)).toBeInTheDocument();
    expect(screen.getByText('Held for review')).toBeInTheDocument();
    expect(screen.getByText('Sent')).toBeInTheDocument();
    expect(screen.getByText('To')).toBeInTheDocument();
    expect(screen.getByText('Somchai J. (*******2109)')).toBeInTheDocument();
    expect(screen.getAllByText('21 Nov 2026, 10:00').length).toBeGreaterThan(0);
    expect(screen.getByText(/at most 24 hours/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel transfer' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to your transfers' })).toHaveAttribute(
      'href',
      '/transfers',
    );
  });

  it.each([
    [404, 'NOT_FOUND'],
    [400, 'VALIDATION_ERROR'],
  ])('answers %i with one generic not-found message', async (status, code) => {
    setup({ 'GET /api/v1/transfers/not-mine': () => errorResponse(status, code, 'id: bad') });
    renderRoute('/transfers/not-mine');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(TRANSFER_NOT_FOUND_MESSAGE);
    expect(alert).not.toHaveTextContent('id: bad');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it.each([
    ['the not-found message', 404, 'NOT_FOUND'],
    ['another load error', 500, 'SYSTEM_ERROR'],
  ])('a re-render with %s does not take the focus back', async (_name, status, code) => {
    setup({ [`GET ${DETAIL}`]: () => errorResponse(status, code) });
    const router = renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    expect(await screen.findByRole('alert')).toHaveFocus();
    const back = screen.getByRole('link', { name: 'Back to your transfers' });
    back.focus();
    await act(async () => {
      await router.navigate(`/transfers/${HELD_TRANSFER.id}`, { state: { rerender: 1 } });
    });

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(back).toHaveFocus();
  });

  it('shows another load error with a retry', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, HELD_TRANSFER),
      ],
    });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Try again' }));
    expect(await screen.findByText(HELD_TRANSFER.reference)).toBeInTheDocument();
  });

  it.each([
    ['a received transfer', RECEIVED_TRANSFER],
    ['a completed transfer', COMPLETED_TRANSFER],
    ['a cancelled transfer', CANCELLED_TRANSFER],
    ['an expired transfer', EXPIRED_TRANSFER],
    ['a received transfer marked held', { ...HELD_TRANSFER, direction: 'RECEIVED' }],
  ])('offers no cancel for %s', async (_name, transfer) => {
    setup({ [`GET /api/v1/transfers/${transfer.id}`]: () => jsonResponse(200, transfer) });
    renderRoute(`/transfers/${transfer.id}`);

    expect(await screen.findByText(transfer.reference)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
  });

  it('asks for confirmation; "Keep the transfer" sends nothing and returns focus', async () => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, HELD_TRANSFER) });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    const dialog = within(screen.getByRole('region', { name: 'Cancel this transfer?' }));
    expect(dialog.getByRole('heading', { name: 'Cancel this transfer?' })).toHaveFocus();
    expect(dialog.getByText(CANCEL_WARNING)).toBeInTheDocument();
    await user.click(dialog.getByRole('button', { name: 'Keep the transfer' }));

    expect(screen.getByRole('button', { name: 'Cancel transfer' })).toHaveFocus();
    expect(http.callsTo('POST', CANCEL)).toHaveLength(0);
  });

  it('closes the confirmation on Escape', async () => {
    const user = userEvent.setup();
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, HELD_TRANSFER) });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('region', { name: 'Cancel this transfer?' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel transfer' })).toHaveFocus();
  });

  it('cancels once on a double click and shows the cancelled transfer', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, HELD_TRANSFER),
      [`POST ${CANCEL}`]: () => answer.promise,
    });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    await user.dblClick(screen.getByRole('button', { name: 'Yes, cancel the transfer' }));
    expect(screen.getByRole('button', { name: 'Cancelling…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Keep the transfer' })).toBeDisabled();
    answer.resolve(jsonResponse(200, CANCELLED_TRANSFER));

    expect(await screen.findByText(CANCELLED_MESSAGE)).toHaveFocus();
    expect(screen.getByText('Cancelled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
    const calls = http.callsTo('POST', CANCEL);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('a refetch still in flight when the cancel answers cannot bring the old state back', async () => {
    const user = userEvent.setup();
    const stale = deferred<Response>();
    const http = setup({
      [`GET ${DETAIL}`]: [() => jsonResponse(200, HELD_TRANSFER), () => stale.promise],
      [`POST ${CANCEL}`]: () => jsonResponse(200, CANCELLED_TRANSFER),
    });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    fireEvent.focus(window);
    await waitFor(() => expect(http.callsTo('GET', DETAIL)).toHaveLength(2));
    await user.click(screen.getByRole('button', { name: 'Yes, cancel the transfer' }));
    expect(await screen.findByText(CANCELLED_MESSAGE)).toBeInTheDocument();

    await act(async () => {
      stale.resolve(jsonResponse(200, HELD_TRANSFER));
      await stale.promise;
    });

    expect(screen.getByText('Cancelled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Refreshing…' })).not.toBeInTheDocument();
  });

  it('on 409 INVALID_TRANSFER_STATE explains it and reloads the detail', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, HELD_TRANSFER),
        () => jsonResponse(200, EXPIRED_TRANSFER),
      ],
      [`POST ${CANCEL}`]: () => errorResponse(409, 'INVALID_TRANSFER_STATE'),
    });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    await user.click(screen.getByRole('button', { name: 'Yes, cancel the transfer' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      'This transfer can no longer be cancelled: it has already been decided or has expired.',
    );
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(await screen.findByText('Expired')).toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
  });

  it('shows another cancel error inside the confirmation and allows a retry', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, HELD_TRANSFER),
      [`POST ${CANCEL}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, CANCELLED_TRANSFER),
      ],
    });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }));
    await user.click(screen.getByRole('button', { name: 'Yes, cancel the transfer' }));
    const dialog = within(screen.getByRole('region', { name: 'Cancel this transfer?' }));
    expect(await dialog.findByRole('alert')).toHaveTextContent(/on our side/);
    await user.click(dialog.getByRole('button', { name: 'Yes, cancel the transfer' }));

    expect(await screen.findByText(CANCELLED_MESSAGE)).toBeInTheDocument();
    expect(http.callsTo('POST', CANCEL)).toHaveLength(2);
  });

  it('refreshes on the button and refetches when the window regains focus', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, HELD_TRANSFER),
        () => jsonResponse(200, HELD_TRANSFER),
        () => jsonResponse(200, { ...HELD_TRANSFER, status: 'COMPLETED', ledgerStatus: 'POSTED' }),
      ],
    });
    renderRoute(`/transfers/${HELD_TRANSFER.id}`);

    await screen.findByText(HELD_TRANSFER.reference);
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(http.callsTo('GET', DETAIL)).toHaveLength(2));
    act(() => {
      fireEvent.focus(window);
    });

    expect(await screen.findByText('Posted')).toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(3);
    expect(screen.queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
  });
});
