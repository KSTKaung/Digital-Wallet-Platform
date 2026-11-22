import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { setSession } from '../auth/tokenStore';
import { bangkokToday } from '../format/dateTime';
import { deferred, errorResponse, jsonResponse, makeJwt, stubFetch } from '../test/http';
import { renderRoute } from '../test/render';
import { PROFILE_LOCKED_MESSAGE, PROFILE_SAVED_MESSAGE } from './ProfilePage';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const ADMIN = makeJwt({ sub: 'staff-1', roles: ['ADMIN'], sid: 's2' });

const EMPTY_PROFILE = {
  email: 'a***@example.com',
  phoneNumber: '******5678',
  firstName: null,
  lastName: null,
  dateOfBirth: null,
  countryCode: null,
};
const FULL_PROFILE = {
  ...EMPTY_PROFILE,
  firstName: 'Ada',
  lastName: 'Lovelace',
  dateOfBirth: '1990-05-01',
  countryCode: 'TH',
};

type Handler = Parameters<typeof stubFetch>[0][string];

function stubs(
  put: Handler = () => jsonResponse(200, FULL_PROFILE),
  profile: Record<string, unknown> = EMPTY_PROFILE,
) {
  return stubFetch({
    'GET /api/v1/customers/me': () => jsonResponse(200, profile),
    'PUT /api/v1/customers/me': put,
  });
}

describe('ProfilePage', () => {
  it('shows the masked contact details and what is not set yet', async () => {
    setSession(CUSTOMER, 900);
    stubs();
    renderRoute('/profile');

    expect(screen.getByRole('status')).toHaveTextContent('Loading your profile…');
    expect(await screen.findByText('a***@example.com')).toBeInTheDocument();
    expect(screen.getByText('******5678')).toBeInTheDocument();
    expect(screen.getByText(/taken from your approved KYC application/)).toBeInTheDocument();
    expect(screen.getAllByText('Not set')).toHaveLength(2);
    expect(screen.getByLabelText('Date of birth')).toHaveValue('');
    expect(screen.getByLabelText('Date of birth')).toHaveAttribute('max', bangkokToday());
    expect(screen.getByLabelText('Country code')).toHaveAccessibleDescription(
      'The 2-letter ISO country code, for example TH for Thailand.',
    );
  });

  it('shows a saved profile with the legal name, date and country name', async () => {
    setSession(CUSTOMER, 900);
    stubs(undefined, FULL_PROFILE);
    renderRoute('/profile');

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByText('1 May 1990')).toBeInTheDocument();
    expect(screen.getByText('Thailand (TH)')).toBeInTheDocument();
    expect(screen.getByLabelText('Date of birth')).toHaveValue('1990-05-01');
    expect(screen.getByLabelText('Country code')).toHaveValue('TH');
  });

  it('saves the date of birth and an upper-cased country, then shows the notice', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Date of birth'), '1990-05-01');
    await user.type(screen.getByLabelText('Country code'), 'th');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('status')).toHaveTextContent(PROFILE_SAVED_MESSAGE);
    const [put] = http.callsTo('PUT', '/api/v1/customers/me');
    expect(JSON.parse(put!.body!)).toEqual({ dateOfBirth: '1990-05-01', countryCode: 'TH' });
    expect(put!.headers.get('Authorization')).toBe(`Bearer ${CUSTOMER}`);
    expect(screen.getByText('Thailand (TH)')).toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('checks the fields before sending and focuses the first invalid one', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Country code'), 'T1');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(screen.getByLabelText('Date of birth')).toHaveFocus();
    expect(screen.getByLabelText('Date of birth')).toHaveAccessibleDescription(
      'Enter your date of birth.',
    );
    expect(screen.getByLabelText('Country code')).toHaveAccessibleDescription(
      /Enter 2 letters, for example TH\./,
    );
    expect(http.callsTo('PUT', '/api/v1/customers/me')).toHaveLength(0);
  });

  it('refuses a future date of birth before sending', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubs();
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Date of birth'), '2999-01-01');
    await user.type(screen.getByLabelText('Country code'), 'TH');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(screen.getByLabelText('Date of birth')).toHaveAccessibleDescription(
      'Date of birth cannot be in the future.',
    );
    expect(http.callsTo('PUT', '/api/v1/customers/me')).toHaveLength(0);
  });

  it('marks the field a server validation error names', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubs(() =>
      errorResponse(400, 'VALIDATION_ERROR', 'countryCode: must be an ISO 3166-1 alpha-2 code'),
    );
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Date of birth'), '1990-05-01');
    await user.type(screen.getByLabelText('Country code'), 'XX');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Country must be an ISO 3166-1 alpha-2 code.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    expect(screen.getByLabelText('Country code')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Country code')).toHaveAccessibleDescription(
      /Country must be an ISO 3166-1 alpha-2 code\./,
    );
  });

  it('explains the lock on 409 INVALID_KYC_STATE', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    stubs(() => errorResponse(409, 'INVALID_KYC_STATE', 'locked'), FULL_PROFILE);
    renderRoute('/profile');

    await user.clear(await screen.findByLabelText('Country code'));
    await user.type(screen.getByLabelText('Country code'), 'JP');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(PROFILE_LOCKED_MESSAGE);
    expect(alert).not.toHaveTextContent('locked');
    expect(screen.getByText('Thailand (TH)')).toBeInTheDocument();
  });

  it('sends one request for a double click', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const pending = deferred<Response>();
    const http = stubs(() => pending.promise);
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Date of birth'), '1990-05-01');
    await user.type(screen.getByLabelText('Country code'), 'TH');
    await user.dblClick(screen.getByRole('button', { name: 'Save' }));

    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    pending.resolve(jsonResponse(200, FULL_PROFILE));
    expect(await screen.findByText(PROFILE_SAVED_MESSAGE)).toBeInTheDocument();
    expect(http.callsTo('PUT', '/api/v1/customers/me')).toHaveLength(1);
  });

  it('shows a load error with its reference and retries', async () => {
    setSession(CUSTOMER, 900);
    const user = userEvent.setup();
    const http = stubFetch({
      'GET /api/v1/customers/me': [
        () => errorResponse(500, 'SYSTEM_ERROR'),
        () => jsonResponse(200, EMPTY_PROFILE),
      ],
    });
    renderRoute('/profile');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side.');
    expect(alert).toHaveTextContent('Reference: srv-1');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('a***@example.com')).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/customers/me')).toHaveLength(2);
  });

  it('is not available for a staff role and makes no profile call', async () => {
    setSession(ADMIN, 900);
    const http = stubs();
    renderRoute('/profile');

    expect(screen.getByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
    expect(http.callsTo('GET', '/api/v1/customers/me')).toHaveLength(0);
  });

  it('writes nothing to web storage', async () => {
    setSession(CUSTOMER, 900);
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const user = userEvent.setup();
    stubs();
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Date of birth'), '1990-05-01');
    await user.type(screen.getByLabelText('Country code'), 'TH');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(PROFILE_SAVED_MESSAGE);

    expect(setItem).not.toHaveBeenCalled();
  });
});
