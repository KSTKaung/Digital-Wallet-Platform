import type { RouteObject } from 'react-router';
import { RequireAuth } from '../auth/RequireAuth';
import { RequireRole } from '../auth/RequireRole';
import { CUSTOMER_ROLES, STAFF_ROLES } from '../auth/roles';
import { AdminHome } from '../pages/admin/AdminHome';
import { CustomerWalletPage } from '../pages/admin/CustomerWalletPage';
import { KycQueuePage } from '../pages/admin/KycQueuePage';
import { KycReviewPage } from '../pages/admin/KycReviewPage';
import { TransferReviewPage } from '../pages/admin/TransferReviewPage';
import { TransferReviewQueuePage } from '../pages/admin/TransferReviewQueuePage';
import { ADMIN_ROLES, COMPLIANCE_ROLES } from '../pages/admin/staffNav';
import { KycPage } from '../pages/kyc/KycPage';
import { ProfilePage } from '../pages/ProfilePage';
import { ChangePasswordPage } from '../pages/auth/ChangePasswordPage';
import { LoginPage } from '../pages/auth/LoginPage';
import { PasswordResetPage } from '../pages/auth/PasswordResetPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { VerifyPage } from '../pages/auth/VerifyPage';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { RouteErrorPage } from '../pages/RouteErrorPage';
import { TransactionsPage } from '../pages/transactions/TransactionsPage';
import { TransferDetailPage } from '../pages/transfer/TransferDetailPage';
import { TransferPage } from '../pages/transfer/TransferPage';
import { TransfersPage } from '../pages/transfer/TransfersPage';
import { MoneyMovementPage } from '../pages/wallet/MoneyMovementPage';
import { RootLayout } from './RootLayout';

/**
 * The application's route table. App.tsx mounts it in a browser router; tests mount the same
 * table in a memory router. The guards are for the UI only (doc 05 §7.1): the server refuses
 * every call a role may not make.
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
      {
        element: <RequireRole roles={CUSTOMER_ROLES} />,
        children: [
          { path: 'profile', element: <ProfilePage /> },
          { path: 'kyc', element: <KycPage /> },
          { path: 'deposit', element: <MoneyMovementPage key="deposit" kind="deposit" /> },
          {
            path: 'withdraw',
            element: <MoneyMovementPage key="withdrawal" kind="withdrawal" />,
          },
          { path: 'transfer', element: <TransferPage /> },
          { path: 'transfers', element: <TransfersPage /> },
          { path: 'transfers/:id', element: <TransferDetailPage /> },
          { path: 'transactions', element: <TransactionsPage /> },
        ],
      },
      {
        // The staff area (doc 05 §7.1); each screen is guarded by the roles that may use it.
        path: 'admin',
        element: <RequireRole roles={STAFF_ROLES} />,
        children: [
          { index: true, element: <AdminHome /> },
          {
            element: <RequireRole roles={COMPLIANCE_ROLES} />,
            children: [
              { path: 'kyc', element: <KycQueuePage /> },
              { path: 'kyc/:id', element: <KycReviewPage /> },
              { path: 'transfer-reviews', element: <TransferReviewQueuePage /> },
              { path: 'transfer-reviews/:transferId', element: <TransferReviewPage /> },
            ],
          },
          {
            element: <RequireRole roles={ADMIN_ROLES} />,
            children: [{ path: 'customers', element: <CustomerWalletPage /> }],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
