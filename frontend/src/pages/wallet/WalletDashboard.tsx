import { useRef, useState, type KeyboardEvent } from 'react';
import { Link } from 'react-router';
import { describeError, isKycRequired, type DescribedError } from '../../api/errorMessages';
import { freezeWallet, type Wallet } from '../../api/wallet';
import { BangkokTime } from '../../components/BangkokTime';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn, useSubmitGuard } from '../../components/formHooks';
import { KycRequiredNotice } from '../../components/KycRequiredNotice';
import { PRIMARY_BUTTON_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { formatMoney } from '../../format/money';
import { useWalletOverview } from './useWalletOverview';
import { WalletStatusChip } from './WalletChips';
import { LimitsTable } from './WalletLimitsView';
import { canMoveMoney, notActiveExplanation, SIMULATED_MONEY_NOTE, tierLabel } from './walletText';

export const FREEZE_WARNING =
  'A frozen wallet cannot move money: no deposits, withdrawals or transfers. You cannot unfreeze it yourself; only our staff can, so you will need to contact support.';
export const FROZEN_DONE_MESSAGE = 'Your wallet is now frozen.';

const PANEL_CLASSES =
  'space-y-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900';

/**
 * The customer's wallet (doc 02 §2.3; doc 05 §5.2): balance, number, status, tier, creation time
 * in Bangkok time, limits as information, the deposit and withdrawal entry points and the
 * self-service freeze. Before KYC approval the KYC notice is shown instead. Refreshed by a button
 * and on window focus (documentations/08 §4D D7).
 */
export function WalletDashboard() {
  const { load, refreshing, refreshError, reload, retry, patchWallet } = useWalletOverview();

  return (
    <>
      <section aria-labelledby="wallet-heading" className={PANEL_CLASSES}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="wallet-heading" className="text-lg font-medium">
            Wallet
          </h2>
          {load.kind === 'loaded' && (
            <button
              type="button"
              onClick={reload}
              disabled={refreshing}
              aria-busy={refreshing}
              className={SECONDARY_BUTTON_CLASSES}
            >
              {refreshing ? 'Refreshing…' : 'Refresh'}
            </button>
          )}
        </div>
        {load.kind === 'loading' && (
          <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
            Loading your wallet…
          </p>
        )}
        {load.kind === 'kycRequired' && <KycRequiredNotice />}
        {load.kind === 'error' && (
          <ErrorNotice error={load.error}>
            <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
              Try again
            </button>
          </ErrorNotice>
        )}
        {load.kind === 'loaded' && (
          <>
            <WalletDetails wallet={load.overview.wallet} />
            {refreshError && (
              <p className="text-sm font-medium text-red-700 dark:text-red-400">
                The wallet could not be refreshed. {refreshError.message}
                {refreshError.correlationId && ` Reference: ${refreshError.correlationId}`}
              </p>
            )}
            <MoneyActions status={load.overview.wallet.status} />
            <FreezeSection
              status={load.overview.wallet.status}
              onFrozen={(status) => {
                patchWallet({ status });
                reload();
              }}
              onNoWallet={reload}
            />
          </>
        )}
      </section>
      {load.kind === 'loaded' && (
        <section aria-labelledby="limits-heading" className={PANEL_CLASSES}>
          <h2 id="limits-heading" className="text-lg font-medium">
            Limits
          </h2>
          <LimitsTable limits={load.overview.limits} error={load.overview.limitsError} />
        </section>
      )}
    </>
  );
}

function WalletDetails({ wallet }: { wallet: Wallet }) {
  return (
    <>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
        <dt className="font-medium">Available balance</dt>
        <dd className="text-lg font-semibold">
          {formatMoney(wallet.availableBalance, wallet.currency)}
        </dd>
        <dt className="font-medium">Reserved</dt>
        <dd>
          {formatMoney(wallet.reservedBalance, wallet.currency)}{' '}
          <span className="text-sm text-slate-600 dark:text-slate-400">
            (held for transfers under review)
          </span>
        </dd>
        <dt className="font-medium">Wallet number</dt>
        <dd>{wallet.walletNumber ?? '—'}</dd>
        <dt className="font-medium">Status</dt>
        <dd>
          <WalletStatusChip status={wallet.status} />
        </dd>
        <dt className="font-medium">Tier</dt>
        <dd>{tierLabel(wallet.tier)}</dd>
        <dt className="font-medium">Created</dt>
        <dd>
          <BangkokTime value={wallet.createdAt} />
        </dd>
      </dl>
      <p className="text-sm text-slate-600 dark:text-slate-400">{SIMULATED_MONEY_NOTE}</p>
    </>
  );
}

/** Deposit and withdraw entry points; for a wallet that is not active, why they are missing. */
function MoneyActions({ status }: { status: string | undefined }) {
  const explanation = notActiveExplanation(status);
  if (explanation !== null) {
    return (
      <p className="rounded-md border border-amber-400 bg-amber-50 p-3 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
        {explanation}
      </p>
    );
  }
  return (
    <div className="flex flex-wrap gap-2">
      <Link to="/deposit" className={PRIMARY_BUTTON_CLASSES}>
        Deposit
      </Link>
      <Link to="/withdraw" className={PRIMARY_BUTTON_CLASSES}>
        Withdraw
      </Link>
    </div>
  );
}

/**
 * Self-service freeze with an inline confirm step (Escape or "Cancel" closes it and returns focus
 * to the Freeze button). The session continues; only staff unfreeze. A repeated freeze changes
 * nothing on the server, and the button is guarded against a double click.
 */
function FreezeSection({
  status,
  onFrozen,
  onNoWallet,
}: {
  status: string | undefined;
  onFrozen: (status: string | undefined) => void;
  onNoWallet: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<DescribedError | null>(null);
  const [frozenNow, setFrozenNow] = useState(false);
  const [returnFocus, setReturnFocus] = useState(0);
  const { busy, run } = useSubmitGuard();
  const confirmHeadingRef = useRef<HTMLHeadingElement>(null);
  const freezeButtonRef = useRef<HTMLButtonElement>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  useFocusOn(confirmHeadingRef, confirming);
  useFocusOn(freezeButtonRef, returnFocus);
  useFocusOn(doneRef, frozenNow);

  if (frozenNow && status === 'FROZEN') {
    return (
      <p
        ref={doneRef}
        role="status"
        tabIndex={-1}
        className="rounded-md border border-blue-300 bg-blue-50 p-3 font-medium text-blue-950 focus:outline-2 focus:outline-offset-2 focus:outline-blue-600 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-100"
      >
        {FROZEN_DONE_MESSAGE}
      </p>
    );
  }
  if (!canMoveMoney(status)) {
    return null;
  }

  const cancel = () => {
    if (busy) {
      return;
    }
    setConfirming(false);
    setError(null);
    setReturnFocus((n) => n + 1);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      cancel();
    }
  };

  const confirm = () =>
    void run(async () => {
      setError(null);
      try {
        const result = await freezeWallet();
        setConfirming(false);
        setFrozenNow(true);
        onFrozen(result?.status ?? 'FROZEN');
      } catch (caught) {
        if (isKycRequired(caught)) {
          onNoWallet();
          return;
        }
        setError(describeError(caught));
      }
    });

  if (!confirming) {
    return (
      <div>
        <button
          ref={freezeButtonRef}
          type="button"
          onClick={() => setConfirming(true)}
          className={SECONDARY_BUTTON_CLASSES}
        >
          Freeze wallet
        </button>
      </div>
    );
  }

  return (
    <section
      aria-labelledby="freeze-confirm-heading"
      onKeyDown={onKeyDown}
      className="space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
    >
      <h3
        id="freeze-confirm-heading"
        ref={confirmHeadingRef}
        tabIndex={-1}
        className="font-semibold focus:outline-none"
      >
        Freeze your wallet?
      </h3>
      <p>{FREEZE_WARNING}</p>
      {error && <ErrorNotice error={error} />}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={confirm}
          disabled={busy}
          aria-busy={busy}
          className={PRIMARY_BUTTON_CLASSES}
        >
          {busy ? 'Freezing…' : 'Yes, freeze my wallet'}
        </button>
        <button type="button" onClick={cancel} disabled={busy} className={SECONDARY_BUTTON_CLASSES}>
          Cancel
        </button>
      </div>
    </section>
  );
}
