import { useEffect, useState } from 'react';
import { describeError, isKycRequired, type DescribedError } from '../api/errorMessages';
import { getWallet, type Wallet } from '../api/wallet';
import { useAuth } from '../auth/authContext';
import { ErrorNotice } from '../components/ErrorNotice';
import { HealthStatus } from '../components/HealthStatus';
import { KycRequiredNotice } from '../components/KycRequiredNotice';

export function HomePage() {
  const { status, hasRole } = useAuth();
  const isCustomer = status === 'signedIn' && hasRole('CUSTOMER');

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Home</h1>
      {isCustomer && <WalletSummary />}
      <HealthStatus />
    </div>
  );
}

type Load =
  | { kind: 'loading' }
  | { kind: 'kycRequired' }
  | { kind: 'error'; error: DescribedError }
  | { kind: 'loaded'; wallet: Wallet };

const AMOUNT_FORMAT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * A placeholder wallet summary for customers; the real dashboard comes with step 5. Before KYC
 * approval there is no wallet (404 WALLET_NOT_FOUND), so the KYC notice is shown instead.
 */
function WalletSummary() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    getWallet(controller.signal)
      .then((wallet) => setLoad({ kind: 'loaded', wallet }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setLoad(
          isKycRequired(error)
            ? { kind: 'kycRequired' }
            : { kind: 'error', error: describeError(error) },
        );
      });
    return () => controller.abort();
  }, []);

  return (
    <section
      aria-labelledby="wallet-heading"
      className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
    >
      <h2 id="wallet-heading" className="text-lg font-medium">
        Wallet
      </h2>
      {load.kind === 'loading' && (
        <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
          Loading your wallet…
        </p>
      )}
      {load.kind === 'kycRequired' && <KycRequiredNotice />}
      {load.kind === 'error' && <ErrorNotice error={load.error} />}
      {load.kind === 'loaded' && (
        <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
          <dt className="font-medium">Wallet number</dt>
          <dd>{load.wallet.walletNumber ?? '—'}</dd>
          <dt className="font-medium">Status</dt>
          <dd>{load.wallet.status ?? '—'}</dd>
          <dt className="font-medium">Available balance</dt>
          <dd>
            {typeof load.wallet.availableBalance === 'number'
              ? `${AMOUNT_FORMAT.format(load.wallet.availableBalance)} ${load.wallet.currency ?? ''}`.trim()
              : '—'}{' '}
            <span className="text-sm text-slate-600 dark:text-slate-400">(simulated money)</span>
          </dd>
        </dl>
      )}
    </section>
  );
}
