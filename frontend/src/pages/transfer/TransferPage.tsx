import { useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { describeError, isKycRequired, type DescribedError } from '../../api/errorMessages';
import { isKeySpentError, useIdempotencyKey } from '../../api/idempotency';
import { createTransfer, getTransfer, type Transfer } from '../../api/transfers';
import { lookupWallet } from '../../api/wallet';
import { BangkokTime } from '../../components/BangkokTime';
import { ErrorNotice } from '../../components/ErrorNotice';
import {
  useCountdown,
  useFocusFirstInvalid,
  useFocusOn,
  useSubmitGuard,
} from '../../components/formHooks';
import { KycRequiredNotice } from '../../components/KycRequiredNotice';
import {
  LINK_CLASSES,
  PRIMARY_BUTTON_CLASSES,
  SECONDARY_BUTTON_CLASSES,
  SubmitButton,
} from '../../components/SubmitButton';
import { TextField } from '../../components/TextField';
import { checkAmount, CURRENCY, formatMoney } from '../../format/money';
import { FROZEN_HINT } from '../wallet/MoneyMovementPage';
import { useWalletOverview } from '../wallet/useWalletOverview';
import { OperationLimits } from '../wallet/WalletLimitsView';
import { notActiveExplanation, SIMULATED_MONEY_NOTE } from '../wallet/walletText';
import { CancelTransfer } from './CancelTransfer';
import { TransferSummary } from './TransferParts';
import {
  checkWalletNumber,
  DESCRIPTION_MAX_LENGTH,
  descriptionError,
  HELD_FOR_REVIEW_NOTE,
  LOOKUP_RATE_LIMITED_MESSAGE,
  RECIPIENT_NOT_FOUND_MESSAGE,
} from './transferText';

/** The looked-up recipient. The full number stays in component state only, never in a URL. */
interface Recipient {
  walletNumber: string;
  maskedName?: string;
  maskedWalletNumber?: string;
}

/** What the confirm step sends: the canonical amount text and the description exactly as typed. */
interface Draft {
  recipient: Recipient;
  amount: string;
  description: string;
}

type Step =
  | { kind: 'recipient' }
  | { kind: 'details'; recipient: Recipient }
  | { kind: 'confirm'; draft: Draft }
  | { kind: 'done'; transfer: Transfer };

const PANEL_CLASSES =
  'space-y-2 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900';

/**
 * Transfer to another wallet (doc 02 §2.3 lookup, §2.4; doc 05 §5.2): recipient → amount →
 * confirm → result. The recipient's masked name and number are shown before confirming
 * (MVP-ADR-20). The browser checks formats only; eligibility, limits, balance and risk are the
 * server's (D8). The confirm step owns the Idempotency-Key (doc 02 §1.4, §7 rule 3).
 */
export function TransferPage() {
  const { load, reload, retry } = useWalletOverview();
  const [step, setStep] = useState<Step>({ kind: 'recipient' });
  const [numberInput, setNumberInput] = useState('');
  const [amountInput, setAmountInput] = useState('');
  const [descriptionInput, setDescriptionInput] = useState('');
  // Focus moves to a step's heading only after the customer moved between steps.
  const [moved, setMoved] = useState(false);

  const goTo = (next: Step) => {
    setMoved(true);
    setStep(next);
  };

  const startAgain = () => {
    setNumberInput('');
    setAmountInput('');
    setDescriptionInput('');
    goTo({ kind: 'recipient' });
  };

  return (
    <section aria-labelledby="transfer-heading" className="mx-auto max-w-xl space-y-5">
      <h1 id="transfer-heading" className="text-xl font-semibold">
        Transfer
      </h1>
      <p className="text-slate-700 dark:text-slate-300">
        Send simulated money to another wallet by its wallet number.
      </p>
      <p className="text-sm text-slate-600 dark:text-slate-400">{SIMULATED_MONEY_NOTE}</p>
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
          <section aria-labelledby="transfer-wallet-heading" className={PANEL_CLASSES}>
            <h2 id="transfer-wallet-heading" className="text-lg font-medium">
              Your wallet
            </h2>
            <p>
              <span className="font-medium">Available balance:</span>{' '}
              {formatMoney(load.overview.wallet.availableBalance, load.overview.wallet.currency)}
            </p>
            <h3 className="font-medium">Transfer limits</h3>
            <OperationLimits
              operation="transfer"
              limits={load.overview.limits}
              error={load.overview.limitsError}
            />
          </section>
          <NotActive status={load.overview.wallet.status} />
          {notActiveExplanation(load.overview.wallet.status) === null && (
            <>
              {step.kind === 'recipient' && (
                <RecipientStep
                  value={numberInput}
                  onChange={setNumberInput}
                  focusHeading={moved}
                  onFound={(recipient) => goTo({ kind: 'details', recipient })}
                  onWalletChanged={reload}
                />
              )}
              {step.kind === 'details' && (
                <DetailsStep
                  recipient={step.recipient}
                  amount={amountInput}
                  description={descriptionInput}
                  onAmount={setAmountInput}
                  onDescription={setDescriptionInput}
                  onChangeRecipient={() => goTo({ kind: 'recipient' })}
                  onReview={(draft) => goTo({ kind: 'confirm', draft })}
                />
              )}
              {step.kind === 'confirm' && (
                <ConfirmStep
                  draft={step.draft}
                  onEdit={() => goTo({ kind: 'details', recipient: step.draft.recipient })}
                  onChangeRecipient={() => goTo({ kind: 'recipient' })}
                  onDone={(transfer) => {
                    goTo({ kind: 'done', transfer });
                    setNumberInput('');
                    setAmountInput('');
                    setDescriptionInput('');
                    reload();
                  }}
                  onWalletChanged={reload}
                />
              )}
            </>
          )}
          {step.kind === 'done' && (
            <ResultStep transfer={step.transfer} onAgain={startAgain} onChanged={reload} />
          )}
        </>
      )}
    </section>
  );
}

/** For a wallet that is not active: why no transfer is possible, and a way back. */
function NotActive({ status }: { status: string | undefined }) {
  const explanation = notActiveExplanation(status);
  if (explanation === null) {
    return null;
  }
  return (
    <div className="space-y-2 rounded-md border border-amber-400 bg-amber-50 p-3 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
      <p>{explanation}</p>
      <p>
        <Link to="/" className={LINK_CLASSES}>
          Back to your wallet
        </Link>
      </p>
    </div>
  );
}

/**
 * Step 1: the wallet number. Only its format is checked here (exactly 11 digits); the server
 * answers one identical `404` for every wallet that cannot receive money, and this step shows one
 * message for it. On `429` the lookup waits for `Retry-After`.
 */
function RecipientStep({
  value,
  onChange,
  focusHeading,
  onFound,
  onWalletChanged,
}: {
  value: string;
  onChange: (value: string) => void;
  focusHeading: boolean;
  onFound: (recipient: Recipient) => void;
  onWalletChanged: () => void;
}) {
  const formRef = useRef<HTMLFormElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const { seconds: waitSeconds, start: startWait } = useCountdown(0);
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [error, setError] = useState<{ described: DescribedError; code?: string } | null>(null);
  const [kycRequired, setKycRequired] = useState(false);
  useFocusOn(headingRef, focusHeading);

  if (kycRequired) {
    return <KycRequiredNotice />;
  }

  const waiting = waitSeconds > 0;

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (waiting) {
      return;
    }
    const checked = checkWalletNumber(value);
    if (!checked.ok) {
      setFieldError(checked.error);
      setError(null);
      focusFirstInvalid();
      return;
    }
    setFieldError(undefined);
    void run(async () => {
      setError(null);
      try {
        const found = await lookupWallet(checked.walletNumber);
        onFound({
          walletNumber: checked.walletNumber,
          maskedName: found?.maskedName,
          maskedWalletNumber: found?.maskedWalletNumber,
        });
      } catch (caught) {
        if (isKycRequired(caught)) {
          setKycRequired(true);
          return;
        }
        if (caught instanceof ApiError) {
          if (caught.status === 429 || caught.status === 503) {
            startWait(caught.retryAfterSeconds ?? 0);
          }
          if (caught.errorCode === 'WALLET_FROZEN') {
            onWalletChanged();
          }
        }
        setError({
          described: describeError(caught, {
            NOT_FOUND: RECIPIENT_NOT_FOUND_MESSAGE,
            RATE_LIMITED: LOOKUP_RATE_LIMITED_MESSAGE,
          }),
          code: caught instanceof ApiError ? caught.errorCode : undefined,
        });
      }
    });
  };

  return (
    <section aria-labelledby="transfer-recipient-heading" className="space-y-3">
      <h2
        id="transfer-recipient-heading"
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-medium focus:outline-none"
      >
        Recipient
      </h2>
      <form ref={formRef} noValidate onSubmit={onSubmit} className="space-y-4">
        <TextField
          label="Recipient's wallet number"
          name="walletNumber"
          autoComplete="off"
          inputMode="numeric"
          value={value}
          onChange={onChange}
          hint="The 11-digit number shown on the recipient's wallet. We show their masked name so you can check it before you send."
          error={fieldError}
          required
        />
        {error && (
          <ErrorNotice error={error.described}>
            {error.code === 'WALLET_FROZEN' && <p className="text-sm">{FROZEN_HINT}</p>}
          </ErrorNotice>
        )}
        <div className="flex flex-wrap gap-2">
          <button
            type="submit"
            disabled={busy || waiting}
            aria-busy={busy}
            className={PRIMARY_BUTTON_CLASSES}
          >
            {busy ? 'Checking…' : waiting ? `Wait ${waitSeconds} s` : 'Find recipient'}
          </button>
          <Link to="/" className={SECONDARY_BUTTON_CLASSES}>
            Cancel
          </Link>
        </div>
      </form>
    </section>
  );
}

function RecipientDetails({ recipient }: { recipient: Recipient }) {
  return (
    <>
      <dt className="font-medium">Recipient</dt>
      <dd>{recipient.maskedName ?? '—'}</dd>
      <dt className="font-medium">Wallet number</dt>
      <dd>{recipient.maskedWalletNumber ?? '—'}</dd>
    </>
  );
}

/** Step 2: the found recipient, then the amount and the optional description. */
function DetailsStep({
  recipient,
  amount,
  description,
  onAmount,
  onDescription,
  onChangeRecipient,
  onReview,
}: {
  recipient: Recipient;
  amount: string;
  description: string;
  onAmount: (value: string) => void;
  onDescription: (value: string) => void;
  onChangeRecipient: () => void;
  onReview: (draft: Draft) => void;
}) {
  const formRef = useRef<HTMLFormElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const [errors, setErrors] = useState<{ amount?: string; description?: string }>({});
  useFocusOn(headingRef, true);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const checked = checkAmount(amount);
    const next = {
      amount: checked.ok ? undefined : checked.error,
      description: descriptionError(description),
    };
    setErrors(next);
    if (!checked.ok || next.description) {
      focusFirstInvalid();
      return;
    }
    onReview({ recipient, amount: checked.amount, description });
  };

  return (
    <section aria-labelledby="transfer-details-heading" className="space-y-3">
      <h2
        id="transfer-details-heading"
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-medium focus:outline-none"
      >
        Amount
      </h2>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 rounded-md border border-slate-200 p-3 sm:grid-cols-[auto_1fr] dark:border-slate-700">
        <RecipientDetails recipient={recipient} />
      </dl>
      <button type="button" onClick={onChangeRecipient} className={SECONDARY_BUTTON_CLASSES}>
        Change recipient
      </button>
      <form ref={formRef} noValidate onSubmit={onSubmit} className="space-y-4">
        <TextField
          label={`Amount (${CURRENCY})`}
          name="amount"
          autoComplete="off"
          inputMode="decimal"
          value={amount}
          onChange={onAmount}
          hint="Thai baht, digits only, with up to 2 decimal places, for example 150.50."
          error={errors.amount}
          required
        />
        <TextField
          label="Description (optional)"
          name="description"
          autoComplete="off"
          value={description}
          onChange={onDescription}
          hint={`For your own records, up to ${DESCRIPTION_MAX_LENGTH} characters, on one line. The recipient does not see it.`}
          error={errors.description}
        />
        <div className="flex flex-wrap gap-2">
          <SubmitButton busy={false} busyText="">
            Review
          </SubmitButton>
          <Link to="/" className={SECONDARY_BUTTON_CLASSES}>
            Cancel
          </Link>
        </div>
      </form>
    </section>
  );
}

/**
 * Step 3. Its Idempotency-Key is created when it mounts and sent on every attempt from here: a
 * double click is refused while a request is in flight, and a retry after a network error, a 5xx,
 * a 429 or a 404 reuses the key (none of them leaves a record). After a stored refusal,
 * `RISK_BLOCKED` or `IDEMPOTENCY_KEY_REUSED` the key is renewed; "Edit" and "Change recipient"
 * unmount the step, so the next confirm step gets a new key.
 */
function ConfirmStep({
  draft,
  onEdit,
  onChangeRecipient,
  onDone,
  onWalletChanged,
}: {
  draft: Draft;
  onEdit: () => void;
  onChangeRecipient: () => void;
  onDone: (transfer: Transfer) => void;
  onWalletChanged: () => void;
}) {
  const { key, renew } = useIdempotencyKey();
  const { busy, run } = useSubmitGuard();
  const { seconds: waitSeconds, start: startWait } = useCountdown(0);
  const [error, setError] = useState<{ described: DescribedError; code?: string } | null>(null);
  const [kycRequired, setKycRequired] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, true);

  const confirm = () =>
    void run(async () => {
      setError(null);
      try {
        const result = await createTransfer(
          {
            receiverWalletNumber: draft.recipient.walletNumber,
            amount: draft.amount,
            currency: CURRENCY,
            description: draft.description,
          },
          key,
        );
        renew();
        onDone(result);
      } catch (caught) {
        if (isKeySpentError(caught)) {
          renew();
        }
        if (isKycRequired(caught)) {
          setKycRequired(true);
          return;
        }
        if (caught instanceof ApiError) {
          if (caught.status === 429 || caught.status === 503) {
            startWait(caught.retryAfterSeconds ?? 0);
          }
          if (caught.errorCode === 'WALLET_FROZEN') {
            onWalletChanged();
          }
        }
        setError({
          described: describeError(caught, { NOT_FOUND: RECIPIENT_NOT_FOUND_MESSAGE }),
          code: caught instanceof ApiError ? caught.errorCode : undefined,
        });
      }
    });

  if (kycRequired) {
    return <KycRequiredNotice />;
  }

  const waiting = waitSeconds > 0;
  // A frozen wallet refuses every new key too; only staff can change that (UI only, the server
  // decides).
  const frozen = error?.code === 'WALLET_FROZEN';
  const recipientProblem = error?.code === 'NOT_FOUND' || error?.code === 'SELF_TRANSFER';

  return (
    <section
      aria-labelledby="transfer-confirm-heading"
      className="space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
    >
      <h2
        id="transfer-confirm-heading"
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-semibold focus:outline-none"
      >
        Confirm your transfer
      </h2>
      <p>Check the recipient's name and number before you confirm.</p>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
        <RecipientDetails recipient={draft.recipient} />
        <dt className="font-medium">Amount</dt>
        <dd>{formatMoney(draft.amount, CURRENCY)}</dd>
        {draft.description !== '' && (
          <>
            <dt className="font-medium">Description</dt>
            <dd className="break-words whitespace-pre-wrap">{draft.description}</dd>
          </>
        )}
      </dl>
      <p className="text-sm">{SIMULATED_MONEY_NOTE}</p>
      {error && (
        <ErrorNotice error={error.described}>
          {frozen && <p className="text-sm">{FROZEN_HINT}</p>}
        </ErrorNotice>
      )}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={confirm}
          disabled={busy || waiting || frozen}
          aria-busy={busy}
          className={PRIMARY_BUTTON_CLASSES}
        >
          {busy ? 'Sending…' : waiting ? `Wait ${waitSeconds} s` : 'Confirm transfer'}
        </button>
        <button type="button" onClick={onEdit} disabled={busy} className={SECONDARY_BUTTON_CLASSES}>
          Edit
        </button>
        <button
          type="button"
          onClick={onChangeRecipient}
          disabled={busy}
          className={recipientProblem ? PRIMARY_BUTTON_CLASSES : SECONDARY_BUTTON_CLASSES}
        >
          Change recipient
        </button>
      </div>
    </section>
  );
}

function resultTitle(status: string | undefined): string {
  switch (status) {
    case 'COMPLETED':
      return 'Transfer completed';
    case 'PENDING_REVIEW':
      return 'Transfer held for review';
    case 'CANCELLED':
      return 'Transfer cancelled';
    default:
      return 'Transfer submitted';
  }
}

/**
 * Step 4: `COMPLETED` (reference, masked counterparty, `ledgerStatus`) or `PENDING_REVIEW` (the
 * deadline in Bangkok time and a cancel action).
 */
function ResultStep({
  transfer: created,
  onAgain,
  onChanged,
}: {
  transfer: Transfer;
  onAgain: () => void;
  onChanged: () => void;
}) {
  const [transfer, setTransfer] = useState(created);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, true);
  const title = resultTitle(transfer.status);
  const held = transfer.status === 'PENDING_REVIEW';
  const tone =
    transfer.status === 'COMPLETED'
      ? 'border-green-600 bg-green-50 text-green-950 dark:border-green-700 dark:bg-green-950 dark:text-green-100'
      : 'border-amber-400 bg-amber-50 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100';

  const reloadTransfer = () => {
    onChanged();
    if (transfer.id === undefined) {
      return;
    }
    getTransfer(transfer.id).then(setTransfer, () => undefined);
  };

  return (
    <section
      aria-labelledby="transfer-result-heading"
      className={`space-y-3 rounded-lg border p-4 ${tone}`}
    >
      <h2
        id="transfer-result-heading"
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-semibold focus:outline-none"
      >
        {title}
      </h2>
      {held && (
        <p>
          {HELD_FOR_REVIEW_NOTE} Review deadline: <BangkokTime value={transfer.reviewExpiresAt} />.
        </p>
      )}
      <TransferSummary transfer={transfer} />
      <CancelTransfer
        transfer={transfer}
        onCancelled={(cancelled) => {
          setTransfer(cancelled);
          onChanged();
        }}
        onStale={reloadTransfer}
      />
      <p className="text-sm">{SIMULATED_MONEY_NOTE}</p>
      <div className="flex flex-wrap gap-2">
        {transfer.id !== undefined && (
          <Link
            to={`/transfers/${encodeURIComponent(transfer.id)}`}
            className={SECONDARY_BUTTON_CLASSES}
          >
            View transfer details
          </Link>
        )}
        <Link to="/" className={PRIMARY_BUTTON_CLASSES}>
          Back to your wallet
        </Link>
        <button type="button" onClick={onAgain} className={SECONDARY_BUTTON_CLASSES}>
          Make another transfer
        </button>
      </div>
    </section>
  );
}
