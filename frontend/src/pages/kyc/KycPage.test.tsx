import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { setSession } from '../../auth/tokenStore';
import {
  deferred,
  errorResponse,
  jsonResponse,
  makeJwt,
  stubFetch,
  type RecordedRequest,
} from '../../test/http';
import { renderRoute } from '../../test/render';
import {
  ALREADY_ACTIVE_MESSAGE,
  DETAILS_LOAD_FAILED_MESSAGE,
  DETAILS_UNAVAILABLE_MESSAGE,
  DRAFT_SAVED_MESSAGE,
  APPLICATION_GONE_MESSAGE,
  NOT_CHANGEABLE_MESSAGE,
  SUBMITTED_MESSAGE,
} from './KycPage';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const STAFF = makeJwt({ sub: 'staff-1', roles: ['COMPLIANCE_OFFICER'], sid: 's2' });
const REFERENCE = 'AB1234567';
const DETAIL_PATH = '/api/v1/kyc/applications/app-1';

type Status = {
  status: string;
  applicationId: string | null;
  verificationLevel: string | null;
  walletCreated: boolean;
};
type Handler = (request: RecordedRequest) => Response | Promise<Response>;

const DRAFT = {
  id: 'app-1',
  status: 'DRAFT',
  verificationLevel: 'BASIC',
  documentType: 'PASSPORT',
  documentReference: '*****4567',
  firstName: 'Ada',
  lastName: 'Lovelace',
  rejectionReason: null,
  submittedAt: null,
  decidedAt: null,
  createdAt: '2026-11-19T03:00:00Z',
  updatedAt: '2026-11-19T03:00:00Z',
};
const PENDING = { ...DRAFT, status: 'PENDING_REVIEW', submittedAt: '2026-11-19T10:30:00Z' };
const APPROVED = { ...PENDING, status: 'APPROVED', decidedAt: '2026-11-19T18:05:00Z' };
const REJECTED = {
  ...PENDING,
  status: 'REJECTED',
  rejectionReason: 'The name does not match the document.',
  decidedAt: '2026-11-19T18:05:00Z',
};
const DETAIL_BY_STATUS: Record<string, Record<string, unknown>> = {
  DRAFT,
  PENDING_REVIEW: PENDING,
  APPROVED,
  REJECTED,
};

/** As GET /kyc/status sends it: an application id with every status but NONE (D11). */
const status = (
  s: string,
  level: string | null = 'BASIC',
  walletCreated = false,
  applicationId: string | null = s === 'NONE' ? null : 'app-1',
): Status => ({
  status: s,
  applicationId,
  verificationLevel: s === 'NONE' ? null : level,
  walletCreated,
});

/**
 * Stubs the KYC endpoints, like a fresh page load: no state survives between renders except what
 * the stubs return. `state.status` and `state.application` can change mid-test.
 */
function stubKyc(
  initial: Status,
  overrides: Record<string, Handler | Handler[]> = {},
  application: Record<string, unknown> = DETAIL_BY_STATUS[initial.status] ?? DRAFT,
) {
  const state = { status: initial, application };
  const http = stubFetch({
    'GET /api/v1/kyc/status': () => jsonResponse(200, state.status),
    [`GET ${DETAIL_PATH}`]: () => jsonResponse(200, state.application),
    ...overrides,
  });
  return { http, state };
}

async function fillForm(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(screen.getByLabelText('Document type'), 'PASSPORT');
  await user.type(screen.getByLabelText('Document reference'), REFERENCE);
  await user.type(screen.getByLabelText('First name'), 'Ada');
  await user.type(screen.getByLabelText('Last name'), 'Lovelace');
}

function statusPanel() {
  return within(screen.getByRole('region', { name: 'Current status' }));
}

describe('KycPage by status', () => {
  it('NONE: shows the status and the form to start an application', async () => {
    setSession(CUSTOMER, 900);
    stubKyc(status('NONE'));
    renderRoute('/kyc');

    expect(screen.getByRole('status')).toHaveTextContent('Loading your KYC status…');
    expect(await screen.findByRole('heading', { name: 'Apply' })).toBeInTheDocument();
    expect(statusPanel().getByText('Not started')).toBeInTheDocument();
    expect(statusPanel().getByText('Not created yet')).toBeInTheDocument();
    expect(screen.getByLabelText('Verification level')).toHaveValue('BASIC');
    expect(screen.getByLabelText('Document type')).toHaveValue('ID_CARD');
    expect(screen.getByLabelText('Document reference')).toHaveAttribute('maxlength', '50');
    expect(screen.getByLabelText('First name')).toHaveAttribute('autocomplete', 'given-name');
    expect(screen.getByLabelText('Verification level')).toHaveAccessibleDescription(
      /no upload and no selfie/,
    );
  });

  it('NONE: loads no application detail', async () => {
    setSession(CUSTOMER, 900);
    const { http } = stubKyc(status('NONE'));
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Apply' });
    expect(http.calls.filter((c) => c.path.startsWith('/api/v1/kyc/applications'))).toHaveLength(0);
  });

  it('DRAFT after a reload: loads the draft by the status id, then edits and submits it', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http, state } = stubKyc(status('DRAFT'), {
      [`PUT ${DETAIL_PATH}`]: () => jsonResponse(200, { ...DRAFT, lastName: 'King' }),
      [`POST ${DETAIL_PATH}/submit`]: () => {
        state.status = status('PENDING_REVIEW');
        return jsonResponse(200, { ...PENDING, lastName: 'King' });
      },
    });
    renderRoute('/kyc');

    const draft = within(await screen.findByRole('region', { name: 'Your draft application' }));
    expect(statusPanel().getByText('Draft (not submitted)')).toBeInTheDocument();
    expect(draft.getByText('Ada Lovelace')).toBeInTheDocument();
    expect(draft.getByText('*****4567')).toBeInTheDocument();
    expect(draft.getByText('Created').closest('dl')).toHaveTextContent(
      'Created19 Nov 2026, 10:00 (Bangkok time)',
    );
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(1);

    await user.click(draft.getByRole('button', { name: 'Edit draft' }));
    await user.clear(screen.getByLabelText('Last name'));
    await user.type(screen.getByLabelText('Last name'), 'King');
    await user.type(screen.getByLabelText('Document reference'), REFERENCE);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Ada King')).toBeInTheDocument();
    expect(http.callsTo('PUT', DETAIL_PATH)).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Submit for review' }));
    await user.click(screen.getByRole('button', { name: 'Yes, submit' }));
    expect(await screen.findByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    expect(http.callsTo('POST', `${DETAIL_PATH}/submit`)).toHaveLength(1);
  });

  it('PENDING_REVIEW: waiting for review, with the application details', async () => {
    setSession(CUSTOMER, 900);
    stubKyc(status('PENDING_REVIEW', 'FULL'));
    renderRoute('/kyc');

    expect(await screen.findByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    expect(statusPanel().getByText('Verification under review')).toBeInTheDocument();
    expect(statusPanel().getByText('Full')).toBeInTheDocument();
    expect(screen.getByText('Submitted').closest('dl')).toHaveTextContent(
      '19 Nov 2026, 17:30 (Bangkok time)',
    );
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit draft|Submit/ })).not.toBeInTheDocument();
  });

  it('APPROVED with a wallet: links to the wallet', async () => {
    setSession(CUSTOMER, 900);
    stubKyc(status('APPROVED', 'BASIC', true));
    renderRoute('/kyc');

    expect(await screen.findByRole('link', { name: 'Go to your wallet' })).toHaveAttribute(
      'href',
      '/',
    );
    expect(statusPanel().getByText('Created')).toBeInTheDocument();
  });

  it('APPROVED without a wallet yet: says so and offers no link', async () => {
    setSession(CUSTOMER, 900);
    stubKyc(status('APPROVED', 'BASIC', false));
    renderRoute('/kyc');

    expect(await screen.findByText(/Your wallet is not shown yet/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Go to your wallet' })).not.toBeInTheDocument();
  });

  it('REJECTED after a reload: shows the reason, Bangkok times and offers to apply again', async () => {
    setSession(CUSTOMER, 900);
    const { http } = stubKyc(status('REJECTED'));
    renderRoute('/kyc');

    expect(await screen.findByText(REJECTED.rejectionReason)).toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'profile' })).toHaveAttribute('href', '/profile');
    const reapply = within(screen.getByRole('region', { name: 'Apply again' }));
    expect(reapply.getByRole('button', { name: 'Save draft' })).toBeInTheDocument();
    const details = screen.getByText('Decided').closest('dl')!;
    expect(details).toHaveTextContent('Submitted19 Nov 2026, 17:30 (Bangkok time)');
    expect(details).toHaveTextContent('Decided20 Nov 2026, 01:05 (Bangkok time)');
    expect(details).toHaveTextContent('*****4567');
    expect(details).not.toHaveTextContent(REFERENCE);
    expect(within(details).getByText('20 Nov 2026, 01:05', { exact: false })).toHaveAttribute(
      'datetime',
      REJECTED.decidedAt,
    );
  });

  it('REJECTED without a reason: says none was given', async () => {
    setSession(CUSTOMER, 900);
    stubKyc(status('REJECTED'), {}, { ...REJECTED, rejectionReason: null });
    renderRoute('/kyc');

    expect(await screen.findByText('No reason was given.')).toBeInTheDocument();
  });

  it.each(['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'])(
    '%s with no application id: shows a generic message and loads no detail',
    async (s) => {
      setSession(CUSTOMER, 900);
      const { http } = stubKyc(status(s, 'BASIC', false, null));
      renderRoute('/kyc');

      expect(await screen.findByText(DETAILS_UNAVAILABLE_MESSAGE)).toBeInTheDocument();
      expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(0);
      expect(screen.queryByRole('button', { name: /Edit draft|Submit/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    },
  );

  it('shows a detail load error with its reference, keeps the status and retries', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http } = stubKyc(status('DRAFT'), {
      [`GET ${DETAIL_PATH}`]: [
        () => errorResponse(503, 'SERVICE_DEGRADED'),
        () => jsonResponse(200, DRAFT),
      ],
    });
    renderRoute('/kyc');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(DETAILS_LOAD_FAILED_MESSAGE);
    expect(alert).toHaveTextContent('Service busy, try again.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(alert).toHaveFocus();
    expect(statusPanel().getByText('Draft (not submitted)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit for review' })).not.toBeInTheDocument();

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('button', { name: 'Submit for review' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(2);
  });

  it('an unknown status is shown as sent and loads no detail', async () => {
    setSession(CUSTOMER, 900);
    const { http } = stubKyc(status('ON_HOLD'));
    renderRoute('/kyc');

    expect(await screen.findByText('Your application status is ON_HOLD.')).toBeInTheDocument();
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(0);
  });

  it('shows a load error with its reference and retries', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http } = stubKyc(status('NONE'), {
      'GET /api/v1/kyc/status': [
        () => errorResponse(503, 'SERVICE_DEGRADED'),
        () => jsonResponse(200, status('NONE')),
      ],
    });
    renderRoute('/kyc');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Service busy, try again.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('heading', { name: 'Apply' })).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
  });

  it('is not available for a staff role and makes no KYC call', () => {
    setSession(STAFF, 900);
    const { http } = stubKyc(status('NONE'));
    renderRoute('/kyc');

    expect(screen.getByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
    expect(http.calls).toHaveLength(0);
  });
});

describe('KycPage create, edit and submit', () => {
  it('creates a draft, edits it, confirms and submits it', async () => {
    setSession(CUSTOMER, 900);
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const user = userEvent.setup();
    const { http, state } = stubKyc(status('NONE'), {
      'POST /api/v1/kyc/applications': () => jsonResponse(201, DRAFT),
      'PUT /api/v1/kyc/applications/app-1': () =>
        jsonResponse(200, { ...DRAFT, verificationLevel: 'FULL', lastName: 'King' }),
      'POST /api/v1/kyc/applications/app-1/submit': () => {
        state.status = status('PENDING_REVIEW', 'FULL');
        return jsonResponse(200, { ...PENDING, verificationLevel: 'FULL', lastName: 'King' });
      },
    });
    const router = renderRoute('/kyc');

    // Create.
    await screen.findByRole('heading', { name: 'Apply' });
    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByText(DRAFT_SAVED_MESSAGE)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Current status' })).toHaveFocus();
    expect(statusPanel().getByText('Draft (not submitted)')).toBeInTheDocument();
    const [created] = http.callsTo('POST', '/api/v1/kyc/applications');
    expect(JSON.parse(created!.body!)).toEqual({
      verificationLevel: 'BASIC',
      documentType: 'PASSPORT',
      documentReference: REFERENCE,
      firstName: 'Ada',
      lastName: 'Lovelace',
    });
    const draft = within(screen.getByRole('region', { name: 'Your draft application' }));
    expect(draft.getByText('*****4567')).toBeInTheDocument();
    expect(draft.getByText('Passport')).toBeInTheDocument();
    expect(draft.getByText(/19 Nov 2026, 10:00/)).toBeInTheDocument();

    // Edit: the masked reference is never prefilled, so it is entered again.
    await user.click(draft.getByRole('button', { name: 'Edit draft' }));
    expect(screen.getByLabelText('First name')).toHaveValue('Ada');
    expect(screen.getByLabelText('Document type')).toHaveValue('PASSPORT');
    expect(screen.getByLabelText('Document reference')).toHaveValue('');
    expect(screen.getByLabelText('Document reference')).toHaveAccessibleDescription(
      /Enter the reference again/,
    );
    await user.selectOptions(screen.getByLabelText('Verification level'), 'FULL');
    await user.clear(screen.getByLabelText('Last name'));
    await user.type(screen.getByLabelText('Last name'), 'King');
    await user.type(screen.getByLabelText('Document reference'), REFERENCE);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Ada King')).toBeInTheDocument();
    const [edited] = http.callsTo('PUT', '/api/v1/kyc/applications/app-1');
    expect(JSON.parse(edited!.body!)).toEqual({
      verificationLevel: 'FULL',
      documentType: 'PASSPORT',
      documentReference: REFERENCE,
      firstName: 'Ada',
      lastName: 'King',
    });

    // Confirm, step back, confirm again and submit with a double click.
    await user.click(screen.getByRole('button', { name: 'Submit for review' }));
    expect(screen.getByRole('heading', { name: 'Submit your application?' })).toHaveFocus();
    expect(screen.getByText(/cannot be changed/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Not yet' }));
    expect(screen.getByRole('button', { name: 'Submit for review' })).toHaveFocus();
    expect(http.callsTo('POST', '/api/v1/kyc/applications/app-1/submit')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Submit for review' }));
    await user.dblClick(screen.getByRole('button', { name: 'Yes, submit' }));

    expect(await screen.findByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    expect(screen.getByText(SUBMITTED_MESSAGE)).toBeInTheDocument();
    expect(statusPanel().getByText('Verification under review')).toBeInTheDocument();
    expect(statusPanel().getByText('Full')).toBeInTheDocument();
    expect(screen.getByText('Submitted').closest('dl')).toHaveTextContent(
      '19 Nov 2026, 17:30 (Bangkok time)',
    );
    expect(http.callsTo('POST', '/api/v1/kyc/applications/app-1/submit')).toHaveLength(1);

    // KYC content never reaches storage or the URL.
    expect(setItem).not.toHaveBeenCalled();
    expect(router.state.location.pathname).toBe('/kyc');
    expect(router.state.location.search).toBe('');
    expect(router.state.location.state).toBeNull();
  });

  it('checks the form before sending', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http } = stubKyc(status('NONE'));
    renderRoute('/kyc');

    await user.click(await screen.findByRole('button', { name: 'Save draft' }));

    expect(screen.getByLabelText('Document reference')).toHaveFocus();
    expect(screen.getByLabelText('First name')).toHaveAccessibleDescription(
      /Enter the first name\./,
    );
    expect(http.callsTo('POST', '/api/v1/kyc/applications')).toHaveLength(0);
  });

  it('marks the field a server validation error names', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubKyc(status('NONE'), {
      'POST /api/v1/kyc/applications': () =>
        errorResponse(400, 'VALIDATION_ERROR', 'firstName: must contain a letter'),
    });
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Apply' });
    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('First name must contain a letter.');
    expect(screen.getByLabelText('First name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('First name')).toHaveFocus();
  });

  it('409 KYC_ALREADY_ACTIVE on create: explains it and reloads the status', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http, state } = stubKyc(status('NONE'), {
      'POST /api/v1/kyc/applications': () => {
        state.status = status('PENDING_REVIEW');
        return errorResponse(409, 'KYC_ALREADY_ACTIVE', 'An active KYC application already exists');
      },
    });
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Apply' });
    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(ALREADY_ACTIVE_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(await screen.findByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
  });

  it('409 INVALID_KYC_STATE on submit: explains it and shows the latest status', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { state } = stubKyc(status('DRAFT'), {
      'POST /api/v1/kyc/applications/app-1/submit': () => {
        state.status = status('PENDING_REVIEW');
        state.application = PENDING;
        return errorResponse(409, 'INVALID_KYC_STATE', 'KYC application is PENDING_REVIEW');
      },
    });
    renderRoute('/kyc');

    await user.click(await screen.findByRole('button', { name: 'Submit for review' }));
    await user.click(screen.getByRole('button', { name: 'Yes, submit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(NOT_CHANGEABLE_MESSAGE);
    expect(await screen.findByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    expect(screen.queryByText(/KYC application is PENDING_REVIEW/)).not.toBeInTheDocument();
  });

  it('409 INVALID_KYC_STATE on edit: explains it and shows the latest status', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { state } = stubKyc(status('DRAFT'), {
      'PUT /api/v1/kyc/applications/app-1': () => {
        state.status = status('APPROVED', 'BASIC', true);
        state.application = { ...PENDING, status: 'APPROVED' };
        return errorResponse(409, 'INVALID_KYC_STATE');
      },
    });
    renderRoute('/kyc');

    await user.click(await screen.findByRole('button', { name: 'Edit draft' }));
    await user.type(screen.getByLabelText('Document reference'), REFERENCE);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(NOT_CHANGEABLE_MESSAGE);
    expect(await screen.findByRole('link', { name: 'Go to your wallet' })).toBeInTheDocument();
  });

  it('404 on submit: explains it and reloads the status and its application', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http, state } = stubKyc(status('DRAFT'), {
      [`POST ${DETAIL_PATH}/submit`]: () => {
        state.status = status('NONE');
        return errorResponse(404, 'NOT_FOUND');
      },
    });
    renderRoute('/kyc');

    await user.click(await screen.findByRole('button', { name: 'Submit for review' }));
    await user.click(screen.getByRole('button', { name: 'Yes, submit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(APPLICATION_GONE_MESSAGE);
    expect(await screen.findByRole('heading', { name: 'Apply' })).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(1);
  });

  it('submit without date of birth: explains it and links to the profile', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubKyc(status('DRAFT'), {
      'POST /api/v1/kyc/applications/app-1/submit': () =>
        errorResponse(
          400,
          'VALIDATION_ERROR',
          'dateOfBirth: must be set in the profile before submitting',
        ),
    });
    renderRoute('/kyc');

    await user.click(await screen.findByRole('button', { name: 'Submit for review' }));
    await user.click(screen.getByRole('button', { name: 'Yes, submit' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Date of birth must be set in the profile before submitting.');
    expect(within(alert).getByRole('link', { name: 'Update your profile' })).toHaveAttribute(
      'href',
      '/profile',
    );
    expect(screen.getByRole('button', { name: 'Yes, submit' })).toBeEnabled();
  });

  it('keeps the draft reachable when moving to another page and back', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http, state } = stubKyc(status('NONE'), {
      'POST /api/v1/kyc/applications': () => {
        state.status = status('DRAFT');
        return jsonResponse(201, DRAFT);
      },
      'GET /api/v1/customers/me': () => jsonResponse(200, { email: 'a***@example.com' }),
    });
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Apply' });
    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    await screen.findByText(DRAFT_SAVED_MESSAGE);

    const nav = within(screen.getByRole('navigation', { name: 'Main' }));
    await user.click(nav.getByRole('link', { name: 'Profile' }));
    await screen.findByText('a***@example.com');
    await user.click(nav.getByRole('link', { name: 'KYC' }));

    expect(await screen.findByRole('button', { name: 'Submit for review' })).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/applications/app-1')).toHaveLength(1);
  });

  it('after a create, a focus refetch loads the application the status reports', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const { http, state } = stubKyc(status('NONE'), {
      'POST /api/v1/kyc/applications': () => {
        state.status = status('DRAFT');
        return jsonResponse(201, DRAFT);
      },
    });
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Apply' });
    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    await screen.findByText(DRAFT_SAVED_MESSAGE);
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(0);

    state.application = { ...DRAFT, firstName: 'Grace' };
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    expect(await screen.findByText('Grace Lovelace')).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
    expect(http.callsTo('GET', DETAIL_PATH)).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeInTheDocument();
  });

  it('a 404 for the reported application is shown as an error, with no draft actions', async () => {
    setSession(CUSTOMER, 900);
    stubKyc(status('DRAFT'), {
      [`GET ${DETAIL_PATH}`]: () => errorResponse(404, 'NOT_FOUND'),
    });
    renderRoute('/kyc');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(DETAILS_LOAD_FAILED_MESSAGE);
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(screen.queryByRole('button', { name: 'Submit for review' })).not.toBeInTheDocument();
  });
});

describe('KycPage freshness (no polling)', () => {
  it('refreshes with the button, keeping the page while it loads', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const second = deferred<Response>();
    const { http } = stubKyc(status('PENDING_REVIEW'), {
      'GET /api/v1/kyc/status': [
        () => jsonResponse(200, status('PENDING_REVIEW')),
        () => second.promise,
      ],
    });
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Waiting for review' });
    await user.click(screen.getByRole('button', { name: 'Refresh status' }));

    expect(screen.getByRole('button', { name: 'Refreshing…' })).toBeDisabled();
    expect(screen.getByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    second.resolve(jsonResponse(200, status('APPROVED', 'BASIC', true)));

    expect(await screen.findByRole('link', { name: 'Go to your wallet' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh status' })).toBeEnabled();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
  });

  it('refetches when the window regains focus, and never on a timer', async () => {
    setSession(CUSTOMER, 900);
    const { http, state } = stubKyc(status('PENDING_REVIEW'));
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Waiting for review' });
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(1);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(1);

    state.status = status('REJECTED');
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    expect(await screen.findByRole('heading', { name: 'Not approved' })).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
  });

  it('a refetch still in flight when a submit answers cannot bring the draft back', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const stale = deferred<Response>();
    const { http } = stubKyc(status('DRAFT'), {
      'GET /api/v1/kyc/status': [() => jsonResponse(200, status('DRAFT')), () => stale.promise],
      [`POST ${DETAIL_PATH}/submit`]: () => jsonResponse(200, PENDING),
    });
    renderRoute('/kyc');

    await screen.findByRole('region', { name: 'Your draft application' });
    await user.click(screen.getByRole('button', { name: 'Submit for review' }));
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    expect(http.callsTo('GET', '/api/v1/kyc/status')).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'Yes, submit' }));
    expect(await screen.findByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();

    await act(async () => {
      stale.resolve(jsonResponse(200, status('DRAFT')));
      await stale.promise;
      await new Promise((resolve) => setTimeout(resolve, 20));
    });

    expect(screen.getByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit for review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh status' })).toBeEnabled();
  });

  it('keeps the last status and shows the error when a refresh fails', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubKyc(status('PENDING_REVIEW'), {
      'GET /api/v1/kyc/status': [
        () => jsonResponse(200, status('PENDING_REVIEW')),
        () => errorResponse(500, 'SYSTEM_ERROR'),
      ],
    });
    renderRoute('/kyc');

    await screen.findByRole('heading', { name: 'Waiting for review' });
    await user.click(screen.getByRole('button', { name: 'Refresh status' }));

    expect(await screen.findByText(/The status could not be refreshed/)).toHaveTextContent(
      'Reference: srv-1',
    );
    expect(screen.getByRole('heading', { name: 'Waiting for review' })).toBeInTheDocument();
  });
});
