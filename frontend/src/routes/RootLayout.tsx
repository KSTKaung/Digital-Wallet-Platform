import { useRef, useState } from 'react';
import { NavLink, Outlet } from 'react-router';
import { AuthProvider } from '../auth/AuthProvider';
import { useAuth } from '../auth/authContext';

const NAV_LINK_BASE =
  'rounded px-2 py-1 text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600';
const NAV_LINK_IDLE =
  'text-slate-700 hover:bg-slate-200 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white';
const NAV_LINK_ACTIVE =
  'bg-slate-200 text-slate-900 underline underline-offset-4 dark:bg-slate-800 dark:text-white';

const navClass = ({ isActive }: { isActive: boolean }) =>
  `${NAV_LINK_BASE} ${isActive ? NAV_LINK_ACTIVE : NAV_LINK_IDLE}`;

/**
 * Shared page frame: skip link, header with the simulated-money note, main navigation and the
 * routed page in <main>. Later steps add navigation items here (documentations/08 §4D.3).
 * The session provider wraps everything inside the router, so every page can use useAuth().
 */
export function RootLayout() {
  return (
    <AuthProvider>
      <Layout />
    </AuthProvider>
  );
}

function Layout() {
  return (
    <div className="flex min-h-screen flex-col bg-slate-50 font-sans text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2 focus:text-slate-900 focus:shadow"
      >
        Skip to main content
      </a>
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-5xl flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-2xl font-semibold">Digital Wallet</p>
            <p className="text-sm text-slate-600 dark:text-slate-400">
              Portfolio demo — simulated money only.
            </p>
          </div>
          <MainNav />
        </div>
      </header>
      <main id="main-content" tabIndex={-1} className="mx-auto w-full max-w-5xl flex-1 px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}

/**
 * Signed in: account items and Sign out. Signed out: Sign in and Register. While the session is
 * being restored neither set is shown, so the navigation does not flicker between them.
 */
function MainNav() {
  const { status, logout, hasRole } = useAuth();
  const [signingOut, setSigningOut] = useState(false);
  const inFlight = useRef(false);

  const onSignOut = () => {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setSigningOut(true);
    // logout() never throws and navigates to /login; the layout stays mounted, so reset after.
    void logout().finally(() => {
      inFlight.current = false;
      setSigningOut(false);
    });
  };

  return (
    <nav aria-label="Main">
      <ul className="flex flex-wrap items-center gap-2">
        <li>
          <NavLink to="/" end className={navClass}>
            Home
          </NavLink>
        </li>
        {status === 'signedIn' && (
          <>
            {hasRole('CUSTOMER') && (
              <>
                <li>
                  <NavLink to="/profile" className={navClass}>
                    Profile
                  </NavLink>
                </li>
                <li>
                  <NavLink to="/kyc" className={navClass}>
                    KYC
                  </NavLink>
                </li>
              </>
            )}
            <li>
              <NavLink to="/settings/password" className={navClass}>
                Change password
              </NavLink>
            </li>
            <li>
              <button
                type="button"
                onClick={onSignOut}
                disabled={signingOut}
                className={`${NAV_LINK_BASE} ${NAV_LINK_IDLE} disabled:cursor-not-allowed disabled:opacity-70`}
              >
                {signingOut ? 'Signing out…' : 'Sign out'}
              </button>
            </li>
          </>
        )}
        {status === 'signedOut' && (
          <>
            <li>
              <NavLink to="/login" className={navClass}>
                Sign in
              </NavLink>
            </li>
            <li>
              <NavLink to="/register" className={navClass}>
                Register
              </NavLink>
            </li>
          </>
        )}
      </ul>
    </nav>
  );
}
