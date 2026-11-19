// The /login state after a session ends comes from the session layer (the reason recorded in
// the token store), so the AuthProvider listener and the RequireAuth guard agree whichever
// navigation runs last. Before this, a page navigated after `await changePassword()` and had to
// win a render race against RequireAuth's own redirect, which carried only `{ from }`.
import { act, render, screen } from '@testing-library/react';
import { createMemoryRouter, Outlet, RouterProvider, useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { makeJwt, stubFetch } from '../test/http';
import { AuthContext, loginStateFor, type AuthContextValue } from './authContext';
import { AuthProvider } from './AuthProvider';
import { RequireAuth } from './RequireAuth';
import { clearSession, endSession, getSessionEndReason, setSession } from './tokenStore';

const CUSTOMER = makeJwt({ sub: 'cust-1', roles: ['CUSTOMER'], sid: 's1' });

function LoginProbe() {
  return <pre data-testid="login-state">{JSON.stringify(useLocation().state ?? null)}</pre>;
}

const children = [
  { path: '/login', element: <LoginProbe /> },
  { path: '/elsewhere', element: <p>Elsewhere</p> },
  {
    path: '/settings/password',
    element: (
      <RequireAuth>
        <p>Guarded page</p>
      </RequireAuth>
    ),
  },
];

function renderWithProvider(path: string) {
  const router = createMemoryRouter(
    [
      {
        element: (
          <AuthProvider>
            <Outlet />
          </AuthProvider>
        ),
        children,
      },
    ],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

/** RequireAuth alone, with no AuthProvider listener that could navigate first. */
function renderGuardOnly(path: string) {
  const value: AuthContextValue = {
    status: 'signedOut',
    user: null,
    login: async () => {
      throw new Error('unused');
    },
    logout: async () => undefined,
    hasRole: () => false,
  };
  const router = createMemoryRouter(
    [
      {
        element: (
          <AuthContext.Provider value={value}>
            <Outlet />
          </AuthContext.Provider>
        ),
        children,
      },
    ],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('loginStateFor', () => {
  it('maps each end reason to the /login state', () => {
    expect(loginStateFor('passwordChanged', '/x')).toEqual({ notice: 'passwordChanged' });
    expect(loginStateFor('sessionEnded', '/x')).toEqual({ sessionEnded: true, from: '/x' });
    expect(loginStateFor('loggedOut', '/x')).toBeUndefined();
    expect(loginStateFor(null, '/x')).toEqual({ from: '/x' });
  });
});

describe('RequireAuth after a session ended', () => {
  it('redirects with the passwordChanged notice on its own (no page navigation needed)', () => {
    stubFetch({});
    setSession(CUSTOMER, 900);
    clearSession('passwordChanged');

    const router = renderGuardOnly('/settings/password');

    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ notice: 'passwordChanged' });
  });

  it('redirects with the session-ended notice and the return path', () => {
    stubFetch({});
    setSession(CUSTOMER, 900);
    clearSession('sessionEnded');

    const router = renderGuardOnly('/settings/password');

    expect(router.state.location.state).toEqual({
      sessionEnded: true,
      from: '/settings/password',
    });
  });

  it('redirects without state after a logout', () => {
    stubFetch({});
    setSession(CUSTOMER, 900);
    clearSession('loggedOut');

    const router = renderGuardOnly('/settings/password');

    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toBeNull();
  });

  it('keeps "from" for a visitor who was never signed in', () => {
    stubFetch({});

    const router = renderGuardOnly('/settings/password');

    expect(router.state.location.state).toEqual({ from: '/settings/password' });
  });
});

describe('AuthProvider and RequireAuth together', () => {
  it('a password change on a guarded page ends at /login with only the notice', async () => {
    stubFetch({});
    setSession(CUSTOMER, 900);
    const router = renderWithProvider('/settings/password');
    expect(screen.getByText('Guarded page')).toBeInTheDocument();

    act(() => endSession('passwordChanged'));

    expect(await screen.findByTestId('login-state')).toHaveTextContent(
      '{"notice":"passwordChanged"}',
    );
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ notice: 'passwordChanged' });
  });

  it('a server-ended session on a guarded page keeps the notice and the return path', async () => {
    stubFetch({});
    setSession(CUSTOMER, 900);
    const router = renderWithProvider('/settings/password');

    act(() => endSession());

    expect(await screen.findByTestId('login-state')).toBeInTheDocument();
    expect(router.state.location.state).toEqual({
      sessionEnded: true,
      from: '/settings/password',
    });
  });

  it('forgets the reason once the user leaves /login', async () => {
    stubFetch({});
    setSession(CUSTOMER, 900);
    const router = renderWithProvider('/settings/password');
    act(() => endSession('passwordChanged'));
    await screen.findByTestId('login-state');
    expect(getSessionEndReason()).toBe('passwordChanged');

    await act(() => router.navigate('/elsewhere'));
    expect(getSessionEndReason()).toBeNull();

    await act(() => router.navigate('/settings/password'));
    expect(router.state.location.state).toEqual({ from: '/settings/password' });
  });
});
