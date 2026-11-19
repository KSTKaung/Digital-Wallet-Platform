import { render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { createMemoryRouter, Outlet, RouterProvider, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiRequest } from '../api/client';
import {
  deferred,
  emptyResponse,
  errorResponse,
  installFakeLocks,
  makeJwt,
  stubFetch,
  tokenResponse,
} from '../test/http';
import { useAuth, type LoginLocationState, SESSION_ENDED_MESSAGE } from './authContext';
import { AuthProvider } from './AuthProvider';
import { RequireAuth } from './RequireAuth';
import { RequireRole } from './RequireRole';
import { getAccessToken, setSession } from './tokenStore';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });
const ADMIN = makeJwt({ sub: 'adm-1', roles: ['ADMIN'], sid: 's2' });

function LoginProbe() {
  const state = useLocation().state as LoginLocationState | null;
  return (
    <div>
      <h1>Login page</h1>
      {state?.sessionEnded && <p role="alert">{SESSION_ENDED_MESSAGE}</p>}
      <p>from: {state?.from ?? 'none'}</p>
    </div>
  );
}

function HomeProbe() {
  const { status, user, login, logout, hasRole } = useAuth();
  return (
    <div>
      <p>status: {status}</p>
      <p>user: {user?.sub ?? 'none'}</p>
      <p>customer: {String(hasRole('CUSTOMER'))}</p>
      <button
        type="button"
        onClick={() => void login({ email: 'a@example.com', password: 'p'.repeat(12) })}
      >
        Sign in
      </button>
      <button type="button" onClick={() => void logout()}>
        Sign out
      </button>
      <button type="button" onClick={() => void apiRequest('/api/v1/wallet').catch(() => {})}>
        Load wallet
      </button>
    </div>
  );
}

function renderApp(path: string, { strict = false } = {}) {
  const router = createMemoryRouter(
    [
      {
        element: (
          <AuthProvider>
            <Outlet />
          </AuthProvider>
        ),
        children: [
          { path: '/', element: <HomeProbe /> },
          { path: '/login', element: <LoginProbe /> },
          {
            path: '/private',
            element: (
              <RequireAuth>
                <p>Private content</p>
              </RequireAuth>
            ),
          },
          {
            path: '/nested',
            element: <RequireAuth />,
            children: [{ index: true, element: <p>Nested private</p> }],
          },
          {
            path: '/admin',
            element: (
              <RequireRole roles={['ADMIN']}>
                <p>Admin content</p>
              </RequireRole>
            ),
          },
          {
            path: '/staff',
            element: <RequireRole roles={['ADMIN', 'COMPLIANCE_OFFICER']} />,
            children: [{ index: true, element: <p>Staff outlet</p> }],
          },
        ],
      },
    ],
    { initialEntries: [path] },
  );
  const tree = <RouterProvider router={router} />;
  render(strict ? <StrictMode>{tree}</StrictMode> : tree);
  return router;
}

const cleanups: (() => void)[] = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

describe('AuthProvider session restore', () => {
  it('restores a session after a reload with one silent refresh', async () => {
    const http = stubFetch({ 'POST /api/v1/auth/refresh': () => tokenResponse(CUSTOMER) });

    renderApp('/');

    expect(screen.getByText('status: restoring')).toBeInTheDocument();
    expect(await screen.findByText('status: signedIn')).toBeInTheDocument();
    expect(screen.getByText('user: cust-1')).toBeInTheDocument();
    expect(screen.getByText('customer: true')).toBeInTheDocument();
    expect(http.calls).toHaveLength(1);
    expect(http.calls[0]?.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
  });

  it('sends exactly one refresh under React StrictMode (double effects)', async () => {
    const locks = installFakeLocks();
    cleanups.push(locks.restore);
    const http = stubFetch({ 'POST /api/v1/auth/refresh': () => tokenResponse(CUSTOMER) });

    renderApp('/', { strict: true });

    expect(await screen.findByText('status: signedIn')).toBeInTheDocument();
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(1);
    expect(locks.requests).toHaveLength(1);
  });

  it('becomes signedOut without any notice when there is no session to restore', async () => {
    stubFetch({ 'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED') });

    renderApp('/');

    expect(await screen.findByText('status: signedOut')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('becomes signedOut when the restore fails on the network', async () => {
    stubFetch({
      'POST /api/v1/auth/refresh': () => {
        throw new TypeError('Failed to fetch');
      },
    });

    renderApp('/');

    expect(await screen.findByText('status: signedOut')).toBeInTheDocument();
  });

  it('does not refresh on mount when a session already exists in memory', async () => {
    setSession(CUSTOMER, 900);
    const http = stubFetch({});

    renderApp('/');

    expect(screen.getByText('status: signedIn')).toBeInTheDocument();
    expect(http.calls).toHaveLength(0);
  });
});

describe('login and logout through useAuth', () => {
  it('login stores the session in memory only and logout navigates to /login', async () => {
    const user = userEvent.setup();
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    stubFetch({
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/login': () => tokenResponse(CUSTOMER),
      'POST /api/v1/auth/logout': () => emptyResponse(204),
    });
    const router = renderApp('/');
    await screen.findByText('status: signedOut');

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('status: signedIn')).toBeInTheDocument();
    expect(setItem).not.toHaveBeenCalled();
    expect(router.state.location.search).toBe('');

    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Login page' })).toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('useAuth outside the provider is a programming error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => renderHook(() => useAuth())).toThrow(/inside <AuthProvider>/);
  });
});

describe('session ended by the server', () => {
  it('navigates to /login with a notice in router state, not in the URL', async () => {
    const user = userEvent.setup();
    setSession(CUSTOMER, 900);
    stubFetch({
      'GET /api/v1/wallet': () => errorResponse(401, 'UNAUTHORIZED'),
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });
    const router = renderApp('/');

    await user.click(screen.getByRole('button', { name: 'Load wallet' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(SESSION_ENDED_MESSAGE);
    expect(screen.getByText('from: /')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.search).toBe('');
    expect(getAccessToken()).toBeNull();
  });
});

describe('route guards', () => {
  it('RequireAuth shows only a status while restoring, then the content', async () => {
    const gate = deferred<Response>();
    stubFetch({ 'POST /api/v1/auth/refresh': () => gate.promise });

    renderApp('/private');

    expect(screen.getByRole('status')).toHaveTextContent('Checking your session');
    expect(screen.queryByText('Private content')).not.toBeInTheDocument();
    gate.resolve(tokenResponse(CUSTOMER));
    expect(await screen.findByText('Private content')).toBeInTheDocument();
  });

  it('RequireAuth sends a signed-out user to /login and remembers the page', async () => {
    stubFetch({ 'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED') });

    const router = renderApp('/private');

    expect(await screen.findByRole('heading', { name: 'Login page' })).toBeInTheDocument();
    expect(screen.getByText('from: /private')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('Private content')).not.toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('RequireAuth renders an outlet when used as a layout route', () => {
    setSession(CUSTOMER, 900);
    stubFetch({});

    renderApp('/nested');

    expect(screen.getByText('Nested private')).toBeInTheDocument();
  });

  it('RequireRole hides a page from a role that may not use it', () => {
    setSession(CUSTOMER, 900);
    stubFetch({});

    renderApp('/admin');

    expect(screen.getByRole('heading', { name: 'Not available' })).toBeInTheDocument();
    expect(screen.queryByText('Admin content')).not.toBeInTheDocument();
  });

  it('RequireRole shows the page to an allowed role', () => {
    setSession(ADMIN, 900);
    stubFetch({});

    renderApp('/admin');

    expect(screen.getByText('Admin content')).toBeInTheDocument();
  });

  it('RequireRole accepts any of several roles and renders an outlet', () => {
    setSession(ADMIN, 900);
    stubFetch({});

    renderApp('/staff');

    expect(screen.getByText('Staff outlet')).toBeInTheDocument();
  });

  it('RequireRole sends a signed-out user to /login', async () => {
    stubFetch({ 'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED') });

    renderApp('/admin');

    expect(await screen.findByRole('heading', { name: 'Login page' })).toBeInTheDocument();
  });

  it('a malformed restored token is treated as signed out', async () => {
    stubFetch({ 'POST /api/v1/auth/refresh': () => tokenResponse('x.y.z') });

    renderApp('/private');

    expect(await screen.findByRole('heading', { name: 'Login page' })).toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
  });
});
