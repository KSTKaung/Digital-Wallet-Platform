import type { RouteObject } from 'react-router';
import { RequireAuth } from '../auth/RequireAuth';
import { ChangePasswordPage } from '../pages/auth/ChangePasswordPage';
import { LoginPage } from '../pages/auth/LoginPage';
import { PasswordResetPage } from '../pages/auth/PasswordResetPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { VerifyPage } from '../pages/auth/VerifyPage';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { RouteErrorPage } from '../pages/RouteErrorPage';
import { RootLayout } from './RootLayout';

/**
 * The application's route table. App.tsx mounts it in a browser router; tests mount the same
 * table in a memory router. Later steps add their pages as children of the layout route.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <RootLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: 'verify', element: <VerifyPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'forgot-password', element: <PasswordResetPage key="request" start="request" /> },
      { path: 'reset-password', element: <PasswordResetPage key="code" start="code" /> },
      {
        element: <RequireAuth />,
        children: [{ path: 'settings/password', element: <ChangePasswordPage /> }],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
