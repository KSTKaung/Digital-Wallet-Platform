import { useCallback, useEffect, useState } from 'react';
import { describeError, isKycRequired, type DescribedError } from '../../api/errorMessages';
import { getLimits, getWallet, type Wallet, type WalletLimits } from '../../api/wallet';
import { useRefetchOnFocus } from '../../components/dataHooks';

export interface WalletOverview {
  wallet: Wallet;
  /** Display only (D8); null when they could not be loaded, with the reason in `limitsError`. */
  limits: WalletLimits | null;
  limitsError: DescribedError | null;
}

export type OverviewLoad =
  | { kind: 'loading' }
  | { kind: 'kycRequired' }
  | { kind: 'error'; error: DescribedError }
  | { kind: 'loaded'; overview: WalletOverview };

/**
 * The wallet, then its limits. Limits are asked for only once a wallet exists, and a failure to
 * load them does not hide the wallet.
 */
async function fetchOverview(signal: AbortSignal): Promise<WalletOverview> {
  const wallet = await getWallet(signal);
  try {
    return { wallet, limits: await getLimits(signal), limitsError: null };
  } catch (error) {
    if (signal.aborted) {
      throw error;
    }
    return { wallet, limits: null, limitsError: describeError(error) };
  }
}

/**
 * The caller's wallet and limits, refreshed by `reload` (a refresh button) and when the window
 * regains focus (documentations/08 §4D D7; no polling). A failed refresh keeps the last data and
 * reports the error in `refreshError`; `404 WALLET_NOT_FOUND` / `403 KYC_REQUIRED` become
 * `kycRequired`.
 */
export function useWalletOverview() {
  const [load, setLoad] = useState<OverviewLoad>({ kind: 'loading' });
  const [version, setVersion] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<DescribedError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetchOverview(controller.signal)
      .then((overview) => {
        if (controller.signal.aborted) {
          return;
        }
        setLoad({ kind: 'loaded', overview });
        setRefreshError(null);
        setRefreshing(false);
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setRefreshing(false);
        if (isKycRequired(error)) {
          setRefreshError(null);
          setLoad({ kind: 'kycRequired' });
          return;
        }
        const described = describeError(error);
        setRefreshError(described);
        setLoad((prev) => (prev.kind === 'loaded' ? prev : { kind: 'error', error: described }));
      });
    return () => controller.abort();
  }, [version]);

  const reload = useCallback(() => {
    setRefreshing(true);
    setVersion((n) => n + 1);
  }, []);

  /** Back to the loading state, then load again (the "Try again" of a failed first load). */
  const retry = useCallback(() => {
    setLoad({ kind: 'loading' });
    reload();
  }, [reload]);

  /** Applies a known change at once (e.g. the freeze answer) until the next load replaces it. */
  const patchWallet = useCallback((patch: Partial<Wallet>) => {
    setLoad((prev) =>
      prev.kind === 'loaded'
        ? {
            kind: 'loaded',
            overview: { ...prev.overview, wallet: { ...prev.overview.wallet, ...patch } },
          }
        : prev,
    );
  }, []);

  useRefetchOnFocus(reload, load.kind === 'loaded');

  return { load, refreshing, refreshError, reload, retry, patchWallet };
}
