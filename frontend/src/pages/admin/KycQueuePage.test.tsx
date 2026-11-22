import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { deferred, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import {
  APPLICATION_ID,
  APPROVED_SUMMARY,
  COMPLIANCE,
  CUSTOMER_ID,
  MASKED_REFERENCE,
  PENDING_SUMMARY,
} from '../../test/kycReviewFixtures';
import { renderRoute } from '../../test/render';
import { page } from '../../test/transferFixtures';

const LIST = '/api/v1/admin/kyc/applications';
type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes) {
  setSession(COMPLIANCE, 900);
  return stubFetch(routes);
}

async function findTable() {
  return within(
    await screen.findByRole('table', { name: 'KYC applications, oldest submission first' }),
  );
}

describe('KYC review queue', () => {
  it('lists pending applications with masked name and reference, Bangkok times and links', async () => {
    const http = setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([PENDING_SUMMARY])) });
    renderRoute('/admin/kyc');

    expect(screen.getByRole('status')).toHaveTextContent('Loading applications…');
    const table = await findTable();
    const rows = table.getAllByRole('row');
    expect(rows).toHaveLength(2);
    const row = within(rows[1]!);
    expect(row.getByText('20 Nov 2026, 10:00')).toBeInTheDocument();
    expect(row.getAllByText('(Bangkok time)')).toHaveLength(1);
    expect(row.getByText('Somchai J.')).toBeInTheDocument();
    expect(row.getByText(CUSTOMER_ID)).toBeInTheDocument();
    expect(row.getByText('Basic')).toBeInTheDocument();
    expect(row.getByText('ID card')).toBeInTheDocument();
    expect(row.getByText(MASKED_REFERENCE)).toBeInTheDocument();
    expect(row.getByText('Pending review')).toBeInTheDocument();
    expect(row.getByText('Not decided')).toBeInTheDocument();
    expect(
      row.getByRole('link', { name: 'Review of the application from Somchai J.' }),
    ).toHaveAttribute('href', `/admin/kyc/${APPLICATION_ID}`);
    expect(screen.getByLabelText('Status')).toHaveValue('PENDING_REVIEW');
    expect(screen.getByRole('heading', { name: 'Pending review, page 1' })).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)[0]!.search).toBe('?page=0&size=20&status=PENDING_REVIEW');
    expect(screen.queryByRole('navigation', { name: 'Application pages' })).not.toBeInTheDocument();
  });

  it('filters by status through ?status= and back to the default', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([PENDING_SUMMARY])),
        () => jsonResponse(200, page([APPROVED_SUMMARY])),
        () => jsonResponse(200, page([PENDING_SUMMARY])),
      ],
    });
    const router = renderRoute('/admin/kyc');
    await (await findTable()).findByText('Somchai J.');

    await user.selectOptions(screen.getByLabelText('Status'), 'APPROVED');

    const approved = await findTable();
    expect(await approved.findByText('Malee S.')).toBeInTheDocument();
    expect(approved.getByText('Approved')).toBeInTheDocument();
    expect(approved.getByText('Full')).toBeInTheDocument();
    expect(approved.getByText('Passport')).toBeInTheDocument();
    expect(approved.getByText('21 Nov 2026, 12:30')).toBeInTheDocument();
    expect(
      approved.getByRole('link', { name: 'Details of the application from Malee S.' }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe('?status=APPROVED');
    expect(http.callsTo('GET', LIST)[1]!.search).toBe('?page=0&size=20&status=APPROVED');
    expect(screen.getByRole('heading', { name: 'Approved, page 1' })).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Status'), 'PENDING_REVIEW');
    expect(await (await findTable()).findByText('Somchai J.')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
    expect(http.callsTo('GET', LIST)[2]!.search).toBe('?page=0&size=20&status=PENDING_REVIEW');
  });

  it('pages through ?page= keeping the status, and a new status starts at page 1', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([APPROVED_SUMMARY], 0, 21, 2)),
        () => jsonResponse(200, page([{ ...APPROVED_SUMMARY, maskedName: 'Anan K.' }], 1, 21, 2)),
        () => jsonResponse(200, page([PENDING_SUMMARY])),
      ],
    });
    const router = renderRoute('/admin/kyc?status=APPROVED');

    const pager = within(await screen.findByRole('navigation', { name: 'Application pages' }));
    expect(pager.getByText('Page 1 of 2')).toBeInTheDocument();
    await user.click(pager.getByRole('button', { name: 'Next page' }));

    expect(await screen.findByText('Anan K.')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?status=APPROVED&page=2');
    expect(http.callsTo('GET', LIST)[1]!.search).toBe('?page=1&size=20&status=APPROVED');
    expect(screen.getByRole('heading', { name: 'Approved, page 2' })).toHaveFocus();

    await user.selectOptions(screen.getByLabelText('Status'), 'REJECTED');
    expect(router.state.location.search).toBe('?status=REJECTED');
    expect(http.callsTo('GET', LIST)[2]!.search).toBe('?page=0&size=20&status=REJECTED');
    expect(screen.getByLabelText('Status')).toHaveFocus();
  });

  it.each([
    ['/admin/kyc?status=DRAFT', '?page=0&size=20&status=PENDING_REVIEW'],
    ['/admin/kyc?status=approved', '?page=0&size=20&status=PENDING_REVIEW'],
    ['/admin/kyc?status=REJECTED&page=3', '?page=2&size=20&status=REJECTED'],
    ['/admin/kyc?status=%3Cx%3E&page=abc', '?page=0&size=20&status=PENDING_REVIEW'],
  ])('reads %s and sends only a known status', async (path, query) => {
    const http = setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([PENDING_SUMMARY])) });
    renderRoute(path);

    await (await findTable()).findByText('Somchai J.');
    expect(http.callsTo('GET', LIST)).toHaveLength(1);
    expect(http.callsTo('GET', LIST)[0]!.search).toBe(query);
  });

  it.each([
    ['', 'No applications are waiting for review.'],
    ['?status=APPROVED', 'No approved applications.'],
    ['?status=REJECTED', 'No rejected applications.'],
  ])('shows the empty state for %j', async (query, message) => {
    setup({ [`GET ${LIST}`]: () => jsonResponse(200, page([], 0, 0, 0)) });
    renderRoute(`/admin/kyc${query}`);

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('a page past the end offers the first page', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([], 4, 3, 1)),
        () => jsonResponse(200, page([APPROVED_SUMMARY], 0, 1, 1)),
      ],
    });
    const router = renderRoute('/admin/kyc?status=APPROVED&page=5');

    expect(await screen.findByText('There are no applications on this page.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Go to the first page' }));
    expect(await screen.findByText('Malee S.')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?status=APPROVED');
  });

  it('shows a load error with its reference and loads again on Try again', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${LIST}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, page([PENDING_SUMMARY])),
      ],
    });
    renderRoute('/admin/kyc');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side. Please try again.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await (await findTable()).findByText('Somchai J.')).toBeInTheDocument();
  });

  it('explains a 403 in staff words', async () => {
    setup({ [`GET ${LIST}`]: () => errorResponse(403, 'FORBIDDEN') });
    renderRoute('/admin/kyc');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Your staff role does not allow this action.',
    );
  });

  it('refreshes on the button and on window focus, keeping the rows if a refresh fails', async () => {
    const user = userEvent.setup();
    const later = deferred<Response>();
    const http = setup({
      [`GET ${LIST}`]: [
        () => jsonResponse(200, page([PENDING_SUMMARY])),
        () => later.promise,
        () => errorResponse(503, 'SERVICE_DEGRADED'),
      ],
    });
    renderRoute('/admin/kyc');
    await (await findTable()).findByText('Somchai J.');

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(screen.getByRole('button', { name: 'Refreshing…' })).toBeDisabled();
    await act(async () => {
      later.resolve(jsonResponse(200, page([{ ...PENDING_SUMMARY, maskedName: 'Anan K.' }])));
      await later.promise;
    });
    expect(await screen.findByText('Anan K.')).toBeInTheDocument();

    fireEvent.focus(window);
    expect(
      await screen.findByText(
        /The applications could not be refreshed\. Service busy, try again\./,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Anan K.')).toBeInTheDocument();
    expect(http.callsTo('GET', LIST)).toHaveLength(3);
  });
});
