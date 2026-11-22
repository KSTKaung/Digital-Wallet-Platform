import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import { deferred, errorResponse, jsonResponse, stubFetch } from '../../test/http';
import { COMPLIANCE } from '../../test/kycReviewFixtures';
import { renderRoute } from '../../test/render';
import {
  APPROVED_REVIEW_DETAIL,
  EXPIRED_REVIEW_DETAIL,
  PAST_DEADLINE,
  PENDING_REVIEW_DETAIL,
  RECIPIENT_ID,
  REJECTED_REVIEW_DETAIL,
  REVIEWER_ID,
  SENDER_ID,
  TRANSFER_ID,
} from '../../test/transferReviewFixtures';
import {
  APPROVE_TRANSFER_WARNING,
  APPROVAL_REJECTED_MESSAGE,
  APPROVED_TRANSFER_MESSAGE,
  REJECT_TRANSFER_REASON_FORMAT_HINT,
  REJECT_TRANSFER_REASON_HINT,
  REJECT_TRANSFER_WARNING,
  REJECTED_TRANSFER_MESSAGE,
  TRANSFER_REVIEW_NOT_FOUND_MESSAGE,
} from './transferReviewText';

const DETAIL = `/api/v1/admin/transfer-reviews/${TRANSFER_ID}`;
const APPROVE = `${DETAIL}/approve`;
const REJECT = `${DETAIL}/reject`;
const PAGE = `/admin/transfer-reviews/${TRANSFER_ID}`;
const STALE_MESSAGE =
  'This transfer is no longer held for review: it has already been decided, cancelled by the sender or expired. A decision at or after the review deadline expires the transfer. The latest details are shown.';
const PARTY_MESSAGE =
  'You cannot review a transfer you take part in as the sender or the recipient, and only a compliance officer can decide transfers. Nothing has changed.';
const CONFIRM_APPROVE = 'Yes, approve and send the money';
const CONFIRM_REJECT = 'Yes, reject and return the money';

type Routes = Parameters<typeof stubFetch>[0];

function setup(routes: Routes) {
  setSession(COMPLIANCE, 900);
  return stubFetch(routes);
}

function region(name: string) {
  return within(screen.getByRole('region', { name }));
}

/** The <dd> of a term, inside a section when given. */
function value(term: string, section?: string) {
  const scope = section ? region(section) : screen;
  const dt = scope.getByText(term, { selector: 'dt' });
  return dt.nextElementSibling as HTMLElement;
}

async function openReject(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Reject' }));
  return screen.getByRole('textbox', { name: 'Reason' });
}

async function confirmReject(user: ReturnType<typeof userEvent.setup>, reason: string) {
  const field = await openReject(user);
  await user.type(field, reason);
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  await user.click(screen.getByRole('button', { name: CONFIRM_REJECT }));
}

/** Waits for a (re)loaded detail whose review outcome reads `text`. */
async function waitForOutcome(text: string) {
  await waitFor(() => expect(value('Outcome', 'Review history')).toHaveTextContent(text));
}

describe('Transfer review detail', () => {
  it('shows the transfer, risk, parties and an empty history, with Approve and Reject', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
    renderRoute(PAGE);

    expect(screen.getByRole('status')).toHaveTextContent('Loading the transfer…');
    expect(await screen.findByRole('region', { name: 'Transfer' })).toBeInTheDocument();
    expect(value('Status', 'Transfer')).toHaveTextContent('Held for review');
    expect(value('Reference', 'Transfer')).toHaveTextContent('TRF-20261121-0001');
    expect(value('Amount', 'Transfer')).toHaveTextContent('45,000.00 THB');
    expect(value('Created', 'Transfer')).toHaveTextContent('21 Nov 2026, 09:59 (Bangkok time)');
    expect(value('Held', 'Transfer')).toHaveTextContent('21 Nov 2026, 10:00 (Bangkok time)');
    expect(value('Review deadline', 'Transfer')).toHaveTextContent(
      '1 Jan 2099, 07:00 (Bangkok time)',
    );
    expect(value('Review deadline', 'Transfer')).not.toHaveTextContent('Overdue');
    expect(value('Decided', 'Transfer')).toHaveTextContent('Not decided yet');
    expect(value('Rejection category (customer sees)', 'Transfer')).toHaveTextContent('—');
    expect(value('Ledger', 'Transfer')).toHaveTextContent('Not posted');
    expect(value('Transfer ID', 'Transfer')).toHaveTextContent(TRANSFER_ID);

    expect(value('Score', 'Risk assessment')).toHaveTextContent('70');
    expect(value('Decision', 'Risk assessment')).toHaveTextContent('Manual review (MANUAL_REVIEW)');
    expect(value('Assessed', 'Risk assessment')).toHaveTextContent(
      '21 Nov 2026, 10:00 (Bangkok time)',
    );
    const rules = within(
      region('Risk assessment').getByRole('table', {
        name: 'Triggered rules, highest weight first',
      }),
    ).getAllByRole('row');
    expect(rules).toHaveLength(3);
    expect(rules[1]).toHaveTextContent('Transfer amount (TRANSFER_AMOUNT)40');
    expect(rules[2]).toHaveTextContent('New recipient (NEW_RECIPIENT)30');

    expect(value('Legal name', 'Sender')).toHaveTextContent('Somchai Jaidee');
    expect(value('Wallet number (masked)', 'Sender')).toHaveTextContent('*******8901');
    expect(value('Customer ID', 'Sender')).toHaveTextContent(SENDER_ID);
    expect(value('Legal name', 'Recipient')).toHaveTextContent('Malee Suksan');
    expect(value('Wallet number (masked)', 'Recipient')).toHaveTextContent('*******2345');
    expect(value('Customer ID', 'Recipient')).toHaveTextContent(RECIPIENT_ID);

    expect(
      region('Review history').getByText('Not decided yet: no review outcome is recorded.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to transfer reviews' })).toHaveAttribute(
      'href',
      '/admin/transfer-reviews',
    );
  });

  it('marks a pending transfer past its deadline as overdue, and says when no rule fired', async () => {
    setup({
      [`GET ${DETAIL}`]: () =>
        jsonResponse(200, {
          ...PENDING_REVIEW_DETAIL,
          reviewExpiresAt: PAST_DEADLINE,
          risk: { ...PENDING_REVIEW_DETAIL.risk, rules: [] },
        }),
    });
    renderRoute(PAGE);

    expect(await screen.findByText('Overdue')).toBeInTheDocument();
    expect(value('Review deadline', 'Transfer')).toHaveTextContent('1 Jan 2020, 07:00');
    expect(region('Risk assessment').getByText('No rules were triggered.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
  });

  it('shows a completed transfer with POSTED and the staff approval, without decisions', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, APPROVED_REVIEW_DETAIL) });
    renderRoute(PAGE);

    expect(await screen.findByRole('region', { name: 'Review history' })).toBeInTheDocument();
    expect(value('Status', 'Transfer')).toHaveTextContent('Completed');
    expect(value('Ledger', 'Transfer')).toHaveTextContent('Posted');
    expect(value('Decided', 'Transfer')).toHaveTextContent('21 Nov 2026, 12:30 (Bangkok time)');
    expect(value('Outcome', 'Review history')).toHaveTextContent('Approved (APPROVE)');
    expect(value('Decided by', 'Review history')).toHaveTextContent('Staff (STAFF)');
    expect(value('Requested action', 'Review history')).toHaveTextContent('Approve (APPROVE)');
    expect(value('Actor ID', 'Review history')).toHaveTextContent(REVIEWER_ID);
    expect(value('Reason category', 'Review history')).toHaveTextContent('—');
    expect(value("Reviewer's reason (staff only)", 'Review history')).toHaveTextContent('—');
    expect(value('System reason', 'Review history')).toHaveTextContent('—');
    expect(value('Recorded', 'Review history')).toHaveTextContent(
      '21 Nov 2026, 12:30 (Bangkok time)',
    );
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('shows a staff rejection with its category and the staff-only reason', async () => {
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, REJECTED_REVIEW_DETAIL) });
    renderRoute(PAGE);

    expect(await screen.findByRole('region', { name: 'Review history' })).toBeInTheDocument();
    expect(value('Status', 'Transfer')).toHaveTextContent('Rejected');
    expect(value('Rejection category (customer sees)', 'Transfer')).toHaveTextContent(
      'Declined after review (REVIEW_DECLINED)',
    );
    expect(value('Reason category', 'Review history')).toHaveTextContent(
      'Staff rejection (STAFF_REJECTION)',
    );
    expect(value("Reviewer's reason (staff only)", 'Review history')).toHaveTextContent(
      'Pattern matches a known scam',
    );
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('shows an expiry by a late decision with its system reason, and unknown codes as sent', async () => {
    setup({
      [`GET ${DETAIL}`]: () =>
        jsonResponse(200, {
          ...EXPIRED_REVIEW_DETAIL,
          risk: {
            ...EXPIRED_REVIEW_DETAIL.risk,
            decision: 'NEW_DECISION',
            rules: [{ code: 'NEW_RULE', weight: 5 }],
          },
        }),
    });
    renderRoute(PAGE);

    expect(await screen.findByRole('region', { name: 'Review history' })).toBeInTheDocument();
    expect(value('Status', 'Transfer')).toHaveTextContent('Expired');
    expect(value('Review deadline', 'Transfer')).not.toHaveTextContent('Overdue');
    expect(value('Outcome', 'Review history')).toHaveTextContent('Expired (EXPIRE)');
    expect(value('Decided by', 'Review history')).toHaveTextContent('System (SYSTEM)');
    expect(value('System reason', 'Review history')).toHaveTextContent(
      'Review deadline passed (REVIEW_DEADLINE_PASSED)',
    );
    expect(value('Decision', 'Risk assessment')).toHaveTextContent(/^NEW_DECISION$/);
    expect(region('Risk assessment').getByRole('table')).toHaveTextContent('NEW_RULE5');
  });

  it.each([
    ['404', () => errorResponse(404, 'NOT_FOUND', 'Resource not found')],
    [
      '400 for a malformed id',
      () => errorResponse(400, 'VALIDATION_ERROR', 'transferId: invalid value'),
    ],
  ])('shows one generic not-found message for a %s', async (_name, answer) => {
    setup({ [`GET ${DETAIL}`]: answer });
    renderRoute(PAGE);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(TRANSFER_REVIEW_NOT_FOUND_MESSAGE);
    expect(alert).not.toHaveTextContent('Resource not found');
    expect(alert).not.toHaveTextContent('transferId');
    expect(alert).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('offers Try again after any other load error', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      ],
    });
    renderRoute(PAGE);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Somchai Jaidee')).toBeInTheDocument();
  });

  it('refreshes on the button and on window focus, keeping the detail if a refresh fails', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, PENDING_REVIEW_DETAIL),
        () => jsonResponse(200, APPROVED_REVIEW_DETAIL),
        () => errorResponse(503, 'SERVICE_DEGRADED'),
      ],
    });
    renderRoute(PAGE);
    await screen.findByRole('button', { name: 'Approve' });

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitForOutcome('Approved (APPROVE)');
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();

    fireEvent.focus(window);
    expect(
      await screen.findByText(/The transfer could not be refreshed\. Service busy, try again\./),
    ).toBeInTheDocument();
    expect(value('Outcome', 'Review history')).toHaveTextContent('Approved (APPROVE)');
    expect(http.callsTo('GET', DETAIL)).toHaveLength(3);
  });
});

describe('Approve', () => {
  it('asks for confirmation; Cancel and Escape close it and return focus without a request', async () => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    expect(screen.getByRole('heading', { name: 'Approve this transfer?' })).toHaveFocus();
    expect(screen.getByText(APPROVE_TRANSFER_WARNING)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.keyboard('{Escape}');
    expect(
      screen.queryByRole('heading', { name: 'Approve this transfer?' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveFocus();
    expect(http.callsTo('POST', APPROVE)).toHaveLength(0);
  });

  it('approves once on a double click and shows the committed detail with a focused notice', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: () => answer.promise,
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.dblClick(screen.getByRole('button', { name: CONFIRM_APPROVE }));
    expect(screen.getByRole('button', { name: 'Approving…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await act(async () => {
      answer.resolve(jsonResponse(200, APPROVED_REVIEW_DETAIL));
      await answer.promise;
    });

    expect(await screen.findByText(APPROVED_TRANSFER_MESSAGE)).toHaveFocus();
    expect(value('Status', 'Transfer')).toHaveTextContent('Completed');
    expect(value('Ledger', 'Transfer')).toHaveTextContent('Posted');
    expect(value('Actor ID', 'Review history')).toHaveTextContent(REVIEWER_ID);
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    const posts = http.callsTo('POST', APPROVE);
    expect(posts).toHaveLength(1);
    expect(posts[0]!.headers.has('Idempotency-Key')).toBe(false);
    expect(posts[0]!.body).toBeUndefined();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(1);
  });

  it('says the approval ended in a rejection when a wallet is frozen or closed (case 3)', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: () =>
        jsonResponse(200, {
          ...REJECTED_REVIEW_DETAIL,
          rejectionReasonCategory: 'WALLET_UNAVAILABLE',
          ledgerStatus: null,
        }),
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));

    expect(await screen.findByText(APPROVAL_REJECTED_MESSAGE)).toHaveFocus();
    expect(screen.queryByText(APPROVED_TRANSFER_MESSAGE)).not.toBeInTheDocument();
    expect(value('Status', 'Transfer')).toHaveTextContent('Rejected');
  });

  it('renders ledgerStatus from the answer, not from the status', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: () =>
        jsonResponse(200, { ...APPROVED_REVIEW_DETAIL, ledgerStatus: 'PENDING_POST' }),
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));

    expect(await screen.findByText(APPROVED_TRANSFER_MESSAGE)).toBeInTheDocument();
    expect(value('Ledger', 'Transfer')).toHaveTextContent('Accounting pending');
  });

  it('explains a 403 for a party reviewer, changes nothing and reloads nothing', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: () =>
        errorResponse(403, 'FORBIDDEN', 'A reviewer cannot decide a transfer they are a party to'),
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(PARTY_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).not.toHaveTextContent('party to');
    expect(alert).toHaveFocus();
    expect(
      screen.queryByRole('heading', { name: 'Approve this transfer?' }),
    ).not.toBeInTheDocument();
    expect(value('Status', 'Transfer')).toHaveTextContent('Held for review');
    expect(http.callsTo('GET', DETAIL)).toHaveLength(1);

    // Opening a decision again clears the notice.
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('explains a 409 (decided meanwhile or expired by a late decision) and reloads the detail', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, PENDING_REVIEW_DETAIL),
        () => jsonResponse(200, EXPIRED_REVIEW_DETAIL),
      ],
      [`POST ${APPROVE}`]: () =>
        errorResponse(409, 'INVALID_TRANSFER_STATE', 'Transfer is not pending review'),
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(STALE_MESSAGE);
    expect(alert).not.toHaveTextContent('not pending review');
    expect(alert).toHaveFocus();
    await waitForOutcome('Expired (EXPIRE)');
    expect(value('Status', 'Transfer')).toHaveTextContent('Expired');
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(2);
  });

  it('keeps the confirm step open after another error, so the officer can retry', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: [
        () => errorResponse(503, 'SERVICE_DEGRADED'),
        () => jsonResponse(200, APPROVED_REVIEW_DETAIL),
      ],
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Service busy, try again.');
    expect(alert).toHaveFocus();
    expect(screen.getByRole('heading', { name: 'Approve this transfer?' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));
    expect(await screen.findByText(APPROVED_TRANSFER_MESSAGE)).toBeInTheDocument();
    expect(http.callsTo('POST', APPROVE)).toHaveLength(2);
  });

  it('shows the generic not-found text when the transfer disappears', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: () => errorResponse(404, 'NOT_FOUND', 'Resource not found'),
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TRANSFER_REVIEW_NOT_FOUND_MESSAGE);
  });

  it('ignores Escape while the approval is being sent', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${APPROVE}`]: () => answer.promise,
    });
    renderRoute(PAGE);

    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: CONFIRM_APPROVE }));
    const heading = screen.getByRole('heading', { name: 'Approve this transfer?' });
    fireEvent.keyDown(heading, { key: 'Escape' });
    expect(heading).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approving…' })).toBeDisabled();

    await act(async () => {
      answer.resolve(jsonResponse(200, APPROVED_REVIEW_DETAIL));
      await answer.promise;
    });
    expect(await screen.findByText(APPROVED_TRANSFER_MESSAGE)).toBeInTheDocument();
  });
});

describe('Reject', () => {
  it('requires a reason with the staff-only hint, focusing the field', async () => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    expect(field).toHaveFocus();
    expect(field).toBeRequired();
    expect(field).toHaveAccessibleDescription(
      `${REJECT_TRANSFER_REASON_HINT} ${REJECT_TRANSFER_REASON_FORMAT_HINT}`,
    );

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Enter a reason.')).toBeInTheDocument();
    expect(field).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Reject' })).toHaveFocus();
    expect(http.callsTo('POST', REJECT)).toHaveLength(0);
  });

  it.each([
    ['blank text', '   ', 'Enter a reason.'],
    ['a line break', 'Known{Enter}scam', 'Reason must be on one line, without line breaks.'],
    ['punctuation only', '?!.', 'Reason must contain a letter or a digit.'],
  ])('refuses %s in the browser without a request', async (_name, typed, message) => {
    const user = userEvent.setup();
    const http = setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
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
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.click(field);
    await user.paste('a'.repeat(501));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Reason must have at most 500 characters.')).toBeInTheDocument();
  });

  it('confirms the reason, rejects once on a double click and shows the committed detail', async () => {
    const user = userEvent.setup();
    const answer = deferred<Response>();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${REJECT}`]: () => answer.promise,
    });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'Pattern matches a known scam');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('heading', { name: 'Reject this transfer?' })).toHaveFocus();
    expect(screen.getByText(REJECT_TRANSFER_WARNING)).toBeInTheDocument();
    expect(screen.getByText('Your reason (staff only)').closest('figure')).toHaveTextContent(
      'Pattern matches a known scam',
    );
    await user.dblClick(screen.getByRole('button', { name: CONFIRM_REJECT }));
    expect(screen.getByRole('button', { name: 'Rejecting…' })).toBeDisabled();
    await act(async () => {
      answer.resolve(jsonResponse(200, REJECTED_REVIEW_DETAIL));
      await answer.promise;
    });

    expect(await screen.findByText(REJECTED_TRANSFER_MESSAGE)).toHaveFocus();
    expect(value('Status', 'Transfer')).toHaveTextContent('Rejected');
    expect(value("Reviewer's reason (staff only)", 'Review history')).toHaveTextContent(
      'Pattern matches a known scam',
    );
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    const posts = http.callsTo('POST', REJECT);
    expect(posts).toHaveLength(1);
    expect(JSON.parse(posts[0]!.body!)).toEqual({ reason: 'Pattern matches a known scam' });
    expect(posts[0]!.headers.has('Idempotency-Key')).toBe(false);
  });

  it('goes back to change the reason, keeping the text', async () => {
    const user = userEvent.setup();
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
    renderRoute(PAGE);

    const field = await openReject(user);
    await user.type(field, 'Unusual amount');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Change the reason' }));

    const again = screen.getByRole('textbox', { name: 'Reason' });
    expect(again).toHaveValue('Unusual amount');
    expect(again).toHaveFocus();
  });

  it('puts a server 400 naming the reason on the field', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${REJECT}`]: () =>
        errorResponse(
          400,
          'VALIDATION_ERROR',
          'reason: must not contain control or format characters',
        ),
    });
    renderRoute(PAGE);

    await confirmReject(user, 'Unusual amount');

    const again = await screen.findByRole('textbox', { name: 'Reason' });
    expect(
      await screen.findByText('Reason must not contain control or format characters.'),
    ).toBeInTheDocument();
    expect(again).toHaveAttribute('aria-invalid', 'true');
    expect(again).toHaveValue('Unusual amount');
    expect(again).toHaveFocus();
    expect(http.callsTo('POST', REJECT)).toHaveLength(1);
  });

  it('shows another 400 inside the confirm step', async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${REJECT}`]: () => errorResponse(400, 'VALIDATION_ERROR', 'Malformed request body'),
    });
    renderRoute(PAGE);

    await confirmReject(user, 'Unusual amount');

    expect(await screen.findByRole('alert')).toHaveTextContent('Malformed request body');
    expect(screen.getByRole('heading', { name: 'Reject this transfer?' })).toBeInTheDocument();
  });

  it('explains a 409 and reloads the detail', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: [
        () => jsonResponse(200, PENDING_REVIEW_DETAIL),
        () => jsonResponse(200, APPROVED_REVIEW_DETAIL),
      ],
      [`POST ${REJECT}`]: () => errorResponse(409, 'INVALID_TRANSFER_STATE'),
    });
    renderRoute(PAGE);

    await confirmReject(user, 'Unusual amount');

    expect(await screen.findByRole('alert')).toHaveTextContent(STALE_MESSAGE);
    await waitForOutcome('Approved (APPROVE)');
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(2);
  });

  it('explains a 403 for a party reviewer', async () => {
    const user = userEvent.setup();
    const http = setup({
      [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL),
      [`POST ${REJECT}`]: () => errorResponse(403, 'FORBIDDEN'),
    });
    renderRoute(PAGE);

    await confirmReject(user, 'Unusual amount');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(PARTY_MESSAGE);
    expect(alert).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL)).toHaveLength(1);
  });

  it('Escape closes the reason step and the confirm step, returning focus to Reject', async () => {
    const user = userEvent.setup();
    setup({ [`GET ${DETAIL}`]: () => jsonResponse(200, PENDING_REVIEW_DETAIL) });
    renderRoute(PAGE);

    await openReject(user);
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Reject' })).toHaveFocus();

    const field = await openReject(user);
    await user.type(field, 'Unusual amount');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Reject' })).toHaveFocus();
  });
});
