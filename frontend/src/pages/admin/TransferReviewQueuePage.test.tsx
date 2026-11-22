import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { deferred, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import { COMPLIANCE } from '../../test/kycReviewFixtures';
import { renderRoute } from '../../test/render';
import { page } from '../../test/transferFixtures';
import { OVERDUE_ITEM, PENDING_ITEM, TRANSFER_ID } from '../../test/transferReviewFixtures';
import { QUEUE_OVERDUE_NOTE, QUEUE_PAGING_NOTE } from './transferReviewText';

const LIST = '/api/v1/admin/transfer-reviews';
type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes) {
  setSession(COMPLIANCE, 900);
  return stubFetch(routes);
}

async function findTable() {
  return within(
    await screen.findByRole('table', {
      name: 'Transfers held for review, soonest deadline first',
    }),
  );
}

describe('Transfer review queue', () => {
  it('lists held transfers with amount, Bangkok times, score and a link, and the notes', async () => {
    const http = setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([PENDING_ITEM])) });
    renderRoute('/admin/transfer-reviews');

    expect(screen.getByRole('status')).toHaveTextContent('Loading transfers…');
    expect(screen.getByText(QUEUE_PAGING_NOTE)).toBeInTheDocument();
    expect(screen.getByText(QUEUE_OVERDUE_NOTE)).toBeInTheDocument();
    const table = await findTable();
    const headers = table.getAllByRole('columnheader').map((th) => th.textContent);
    expect(headers).toEqual(['Reference', 'Amount', 'Held', 'Review deadline', 'Score', 'Details']);
    const rows = table.getAllByRole('row');
    expect(rows).toHaveLength(2);
    const row = within(rows[1]!);
    expect(row.getByText('TRF-20261121-0001')).toBeInTheDocument();
    expect(row.getByText('45,000.00 THB')).toBeInTheDocument();
    expect(row.getByText('21 Nov 2026, 10:00')).toBeInTheDocument();
    expect(row.getByText('1 Jan 2099, 07:00')).toBeInTheDocument();
    expect(row.getAllByText('(Bangkok time)')).toHaveLength(2);
    expect(row.getByText('70')).toBeInTheDocument();
    expect(row.queryByText('Overdue')).not.toBeInTheDocument();
    expect(row.getByRole('link', { name: 'Review transfer TRF-20261121-0001' })).toHaveAttribute(
      'href',
      `/admin/transfer-reviews/${TRANSFER_ID}`,
    );
    expect(screen.getByRole('heading', { name: 'Held for review, page 1' })).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)[0]!.search).toBe('?page=0&size=20');
    expect(
      screen.queryByRole('navigation', { name: 'Transfer review pages' }),
    ).not.toBeInTheDocument();
  });

  it('marks a transfer whose deadline has passed as overdue, and shows a missing score as a dash', async () => {
    setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([OVERDUE_ITEM, PENDING_ITEM])) });
    renderRoute('/admin/transfer-reviews');

    const rows = (await findTable()).getAllByRole('row');
    const overdue = within(rows[1]!);
    expect(overdue.getByText('Overdue')).toBeInTheDocument();
    expect(overdue.getByText('1 Jan 2020, 07:00')).toBeInTheDocument();
    expect(overdue.getByText('1,234.50 THB')).toBeInTheDocument();
    expect(overdue.getByText('—')).toBeInTheDocument();
    expect(within(rows[2]!).queryByText('Overdue')).not.toBeInTheDocument();
  });

  it('pages through ?page= (1-based in the URL, 0-based to the API) and focuses the new page', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([PENDING_ITEM], 0, 21, 2)),
        () => jsonResponse(200, page([OVERDUE_ITEM], 1, 21, 2)),
        () => jsonResponse(200, page([PENDING_ITEM], 0, 21, 2)),
      ],
    });
    const router = renderRoute('/admin/transfer-reviews');

    const pager = within(await screen.findByRole('navigation', { name: 'Transfer review pages' }));
    expect(pager.getByText('Page 1 of 2')).toBeInTheDocument();
    await user.click(pager.getByRole('button', { name: 'Next page' }));

    expect(await screen.findByText('TRF-20191231-0002')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?page=2');
    expect(http.callsTo('GET', LIST)[1]!.search).toBe('?page=1&size=20');
    expect(screen.getByRole('heading', { name: 'Held for review, page 2' })).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(await screen.findByText('TRF-20261121-0001')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
    expect(http.callsTo('GET', LIST)[2]!.search).toBe('?page=0&size=20');
  });

  it.each([
    ['/admin/transfer-reviews?page=3', '?page=2&size=20'],
    ['/admin/transfer-reviews?page=0', '?page=0&size=20'],
    ['/admin/transfer-reviews?page=abc', '?page=0&size=20'],
  ])('reads %s', async (path, query) => {
    const http = setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([PENDING_ITEM])) });
    renderRoute(path);

    await (await findTable()).findByText('TRF-20261121-0001');
    expect(http.callsTo('GET', LIST)).toHaveLength(1);
    expect(http.callsTo('GET', LIST)[0]!.search).toBe(query);
  });

  it('shows the empty state', async () => {
    setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([], 0, 0, 0)) });
    renderRoute('/admin/transfer-reviews');

    expect(await screen.findByText('No transfers are waiting for review.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('a page emptied by decisions elsewhere offers the first page', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([], 1, 1, 1)),
        () => jsonResponse(200, page([PENDING_ITEM], 0, 1, 1)),
      ],
    });
    const router = renderRoute('/admin/transfer-reviews?page=2');

    expect(await screen.findByText('There are no transfers on this page.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Go to the first page' }));
    expect(await screen.findByText('TRF-20261121-0001')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('shows a load error with its reference and loads again on Try again', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${LIST}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, page([PENDING_ITEM])),
      ],
    });
    renderRoute('/admin/transfer-reviews');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side. Please try again.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await (await findTable()).findByText('TRF-20261121-0001')).toBeInTheDocument();
  });

  it('explains a 403 in staff words', async () => {
    setup({ [`GET ${LIST}`]: () => errorResponse(403, 'FORBIDDEN') });
    renderRoute('/admin/transfer-reviews');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Your staff role does not allow this action.',
    );
  });

  it('refreshes on the button and on window focus, keeping the rows if a refresh fails', async () => {
    const user = userEvent.setup();
    const later = deferred<Response>();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([PENDING_ITEM])),
        () => later.promise,
        () => errorResponse(503, 'SERVICE_DEGRADED'),
      ],
    });
    renderRoute('/admin/transfer-reviews');
    await (await findTable()).findByText('TRF-20261121-0001');

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(screen.getByRole('button', { name: 'Refreshing…' })).toBeDisabled();
    await act(async () => {
      later.resolve(jsonResponse(200, page([OVERDUE_ITEM])));
      await later.promise;
    });
    expect(await screen.findByText('TRF-20191231-0002')).toBeInTheDocument();
    expect(screen.queryByText('TRF-20261121-0001')).not.toBeInTheDocument();

    fireEvent.focus(window);
    expect(
      await screen.findByText(/The transfers could not be refreshed\. Service busy, try again\./),
    ).toBeInTheDocument();
    expect(screen.getByText('TRF-20191231-0002')).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)).toHaveLength(3);
  });
});
