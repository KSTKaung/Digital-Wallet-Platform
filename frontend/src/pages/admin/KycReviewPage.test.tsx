import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { deferred, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import {
  APPLICATION_ID,
  APPROVED_DETAIL,
  COMPLIANCE,
  CUSTOMER_ID,
  MASKED_REFERENCE,
  PENDING_DETAIL,
  REJECTED_DETAIL,
  REVIEWER_ID,
} from '../../test/kycReviewFixtures';
import { renderRoute } from '../../test/render';
import {
  APPROVE_WARNING,
  APPROVED_MESSAGE,
  KYC_APPLICATION_NOT_FOUND_MESSAGE,
  REJECT_REASON_FORMAT_HINT,
  REJECT_REASON_HINT,
  REJECTED_MESSAGE,
} from './kycReviewText';

const DETAIL = `/api/v1/admin/kyc/applications/${APPLICATION_ID}`;
const APPROVE = `${DETAIL}/approve`;
const REJECT = `${DETAIL}/reject`;
const PAGE = `/admin/kyc/${APPLICATION_ID}`;
const STALE_MESSAGE =
  'This application is no longer pending review: another officer or an earlier decision has already decided it. The latest details are shown.';

type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes) {
  setSession(COMPLIANCE, 900);
  return stubFetch(routes);
}

function value(term: string) {
  const dt = screen.getByText(term, { selector: 'dt' });
  return dt.nextElementSibling as HTMLElement;
}

async function openReject(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Reject' }));
  return screen.getByRole('textbox', { name: 'Reason' });
}

describe('KYC application detail', () => {
  it('shows every field, the masked reference and Bangkok times, with Approve and Reject', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    expect(screen.getByRole('status')).toHaveTextContent('Loading the application…');
    expect(await screen.findByText('Somchai Jaidee')).toBeInTheDocument();
    expect(value('Date of birth (at submission)')).toHaveTextContent('1 May 1990');
    expect(value('Country (at submission)')).toHaveTextContent('Thailand (TH)');
    expect(value('Verification level')).toHaveTextContent('Basic');
    expect(value('Document type')).toHaveTextContent('ID card');
    expect(value('Document reference (masked)')).toHaveTextContent(MASKED_REFERENCE);
    expect(value('Submitted')).toHaveTextContent('20 Nov 2026, 10:00 (Bangkok time)');
    expect(value('Decided')).toHaveTextContent('Not decided yet');
    expect(value('Reviewer ID')).toHaveTextContent('—');
    expect(value('Customer ID')).toHaveTextContent(CUSTOMER_ID);
    expect(value('Application ID')).toHaveTextContent(APPLICATION_ID);
    expect(value('Created')).toHaveTextContent('19 Nov 2026, 10:00 (Bangkok time)');
    expect(value('Last updated')).toHaveTextContent('20 Nov 2026, 10:00 (Bangkok time)');
    expect(screen.queryByText('Rejection reason', { selector: 'dt' })).not.toBeInTheDocument();
    expect(screen.getByText('Pending review')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to KYC reviews' })).toHaveAttribute(
      'href',
      '/admin/kyc',
    );
  });

  it('offers no decision once approved, and shows the reviewer and decision time', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, APPROVED_DETAIL) });
    renderRoute(PAGE);

    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(value('Decided')).toHaveTextContent('21 Nov 2026, 12:30 (Bangkok time)');
    expect(value('Reviewer ID')).toHaveTextContent(REVIEWER_ID);
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('shows the rejection reason of a rejected application', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, REJECTED_DETAIL) });
    renderRoute(PAGE);

    expect(await screen.findByText('Rejected')).toBeInTheDocument();
    expect(value('Rejection reason')).toHaveTextContent('The photo is not readable');
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it.each([
    ['404', () => errorResponse(404, 'NOT_FOUND', 'Resource not found')],
    ['400 for a malformed id', () => errorResponse(400, 'VALIDATION_ERROR', 'id: invalid value')],
  ])('shows one generic not-found message for a %s', async (_name, answer) => {
    setup({ [`GET ${DETAIL}`]: answer });
    renderRoute(PAGE);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(KYC_APPLICATION_NOT_FOUND_MESSAGE);
    expect(alert).not.toHaveTextContent('Resource not found');
    expect(alert).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('offers Try again after any other load error', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, PENDING_DETAIL),
      ],
    });
    renderRoute(PAGE);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Somchai Jaidee')).toBeInTheDocument();
  });

  it('refreshes on the button', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, PENDING_DETAIL),
        () => jsonResponse(200, APPROVED_DETAIL),
      ],
    });
    renderRoute(PAGE);
    await screen.findByText('Pending review');

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(2);
  });
});

describe('Approve', () => {
  it('asks for confirmation; Cancel and Escape close it and return focus without a request', async () => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    expect(screen.getByRole('heading', { name: 'Approve this application?' })).toHaveFocus();
    expect(screen.getByText(APPROVE_WARNING)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.keyboard('{Escape}');
    expect(
      screen.queryByRole('heading', { name: 'Approve this application?' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveFocus();
    expect(http.callsTo('POST', APPROVE)).toHaveLength(0);
  });

  it('approves once on a double click and shows the updated detail with a focused notice', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL),
      [`POST ${APPROVE}`]: () => answer.promise,
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    const confirm = screen.getByRole('button', { name: 'Yes, approve and create the wallet' });
    await user.dblClick(confirm);
    expect(screen.getByRole('button', { name: 'Approving…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await act(async () => {
      answer.resolve(jsonResponse(200, APPROVED_DETAIL));
      await answer.promise;
    });

    expect(await screen.findByText(APPROVED_MESSAGE)).toHaveFocus();
    expect(screen.getByText('Approved')).toBeInTheDocument();
    expect(value('Reviewer ID')).toHaveTextContent(REVIEWER_ID);
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    const posts = http.callsTo('POST', APPROVE);
    expect(posts).toHaveLength(1);
    expect(posts[0]!.headers.has('Idempotency-Key')).toBe(false);
    expect(http.callsTo('GET', DETAIL)).toHaveLength(1);
  });

  it('explains a 409 (decided meanwhile) and reloads the detail', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, PENDING_DETAIL),
        () => jsonResponse(200, APPROVED_DETAIL),
      ],
      [`POST ${APPROVE}`]: () =>
        errorResponse(409, 'INVALID_KYC_STATE', 'KYC application is no longer PENDING_REVIEW'),
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Yes, approve and create the wallet' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(STALE_MESSAGE);
    expect(alert).not.toHaveTextContent('PENDING_REVIEW');
    expect(alert).toHaveFocus();
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(2);
  });

  it('keeps the confirm step open after another error, so the officer can retry', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL),
      [`POST ${APPROVE}`]: [
        () => errorResponse(403, 'FORBIDDEN'),
        () => jsonResponse(200, APPROVED_DETAIL),
      ],
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Yes, approve and create the wallet' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Your staff role does not allow this action.');
    expect(alert).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Yes, approve and create the wallet' }));
    expect(await screen.findByText(APPROVED_MESSAGE)).toBeInTheDocument();
    expect(http.callsTo('POST', APPROVE)).toHaveLength(2);
  });
});

describe('Reject', () => {
  it('asks for a reason with the customer-visible hint, focusing the field', async () => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    expect(field).toHaveFocus();
    expect(field).toBeRequired();
    expect(field).toHaveAccessibleDescription(`${REJECT_REASON_HINT} ${REJECT_REASON_FORMAT_HINT}`);

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Enter a reason.')).toBeInTheDocument();
    expect(field).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Reject' })).toHaveFocus();
    expect(http.callsTo('POST', REJECT)).toHaveLength(0);
  });

  it.each([
    ['a line break', 'Photo{Enter}unreadable', 'Reason must be on one line, without line breaks.'],
    ['punctuation only', '?!.', 'Reason must contain a letter or a digit.'],
  ])('refuses %s in the browser without a request', async (_name, typed, message) => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, typed);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText(message)).toBeInTheDocument();
    expect(field).toHaveFocus();
    expect(http.callsTo('POST', REJECT)).toHaveLength(0);
  });

  it('refuses a reason over 500 characters', async () => {
    const user = userEvent.setup();
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.click(field);
    await user.paste('a'.repeat(501));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Reason must have at most 500 characters.')).toBeInTheDocument();
  });

  it('confirms the reason, rejects once on a double click and shows the updated detail', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL),
      [`POST ${REJECT}`]: () => answer.promise,
    });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'The photo is not readable');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('heading', { name: 'Reject this application?' })).toHaveFocus();
    expect(screen.getByText('Reason the customer will see').closest('figure')).toHaveTextContent(
      'The photo is not readable',
    );
    await user.dblClick(screen.getByRole('button', { name: 'Yes, reject the application' }));
    expect(screen.getByRole('button', { name: 'Rejecting…' })).toBeDisabled();
    await act(async () => {
      answer.resolve(jsonResponse(200, REJECTED_DETAIL));
      await answer.promise;
    });

    expect(await screen.findByText(REJECTED_MESSAGE)).toHaveFocus();
    expect(screen.getByText('Rejected')).toBeInTheDocument();
    expect(value('Rejection reason')).toHaveTextContent('The photo is not readable');
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    const posts = http.callsTo('POST', REJECT);
    expect(posts).toHaveLength(1);
    expect(JSON.parse(posts[0]!.body!)).toEqual({ reason: 'The photo is not readable' });
    expect(posts[0]!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('goes back to change the reason, keeping the text', async () => {
    const user = userEvent.setup();
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'Blurry photo');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Change the reason' }));

    const again = screen.getByRole('textbox', { name: 'Reason' });
    expect(again).toHaveValue('Blurry photo');
    expect(again).toHaveFocus();
  });

  it('shows the server field message when the reason contains the document reference', async () => {
    const user = userEvent.setup();
    const typed = 'Reference AB1234567 is wrong';
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL),
      [`POST ${REJECT}`]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'reason: must not contain the document reference'),
    });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, typed);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Yes, reject the application' }));

    const again = await screen.findByRole('textbox', { name: 'Reason' });
    expect(
      await screen.findByText('Reason must not contain the document reference.'),
    ).toBeInTheDocument();
    expect(again).toHaveAttribute('aria-invalid', 'true');
    expect(again).toHaveValue(typed);
    expect(again).toHaveFocus();
    expect(http.callsTo('POST', REJECT)).toHaveLength(1);
  });

  it('shows another 400 inside the confirm step, naming the field only', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL),
      [`POST ${REJECT}`]: () => errorResponse(400, 'VALIDATION_ERROR', 'Malformed request body'),
    });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'Blurry photo');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Yes, reject the application' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Malformed request body');
    expect(screen.getByRole('heading', { name: 'Reject this application?' })).toBeInTheDocument();
  });

  it('explains a 409 (decided meanwhile) and reloads the detail', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, PENDING_DETAIL),
        () => jsonResponse(200, APPROVED_DETAIL),
      ],
      [`POST ${REJECT}`]: () => errorResponse(409, 'INVALID_KYC_STATE'),
    });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'Blurry photo');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Yes, reject the application' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(STALE_MESSAGE);
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(2);
  });

  it('Escape closes the confirm step and returns focus to Reject', async () => {
    const user = userEvent.setup();
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'Blurry photo');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.keyboard('{Escape}');

    expect(screen.getByRole('button', { name: 'Reject' })).toHaveFocus();
  });
});

describe('Decision in flight', () => {
  it('ignores Escape while the approval is being sent', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_DETAIL),
      [`POST ${APPROVE}`]: () => answer.promise,
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Yes, approve and create the wallet' }));
    const heading = screen.getByRole('heading', { name: 'Approve this application?' });
    fireEvent.keyDown(heading, { key: 'Escape' });
    expect(heading).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approving…' })).toBeDisabled();

    await act(async () => {
      answer.resolve(jsonResponse(200, APPROVED_DETAIL));
      await answer.promise;
    });
    expect(await screen.findByText(APPROVED_MESSAGE)).toBeInTheDocument();
  });
});
