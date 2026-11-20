import { useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { describeError, isKycRequired, type DescribedError } from '../../api/errorMessages';
import { isKeySpentError, useIdempotencyKey } from '../../api/idempotency';
import { deposit, withdraw, type MoneyMovement } from '../../api/wallet';
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
import { useWalletOverview } from './useWalletOverview';
import { LedgerStatusChip } from './WalletChips';
import { OperationLimits } from './WalletLimitsView';
import {
  movementStatusLabel,
  NOTE_MAX_LENGTH,
  noteError,
  notActiveExplanation,
  SIMULATED_MONEY_NOTE,
} from './walletText';

export type MovementKind = 'deposit' | 'withdrawal';

export const FROZEN_HINT = 'Only our staff can unfreeze a wallet; contact support.';

const TEXT: Readonly<
  Record<
    MovementKind,
    {
      title: string;
      intro: string;
      confirmTitle: string;
      confirmButton: string;
      busy: string;
      resultTitle: string;
      again: string;
    }
  >
> = {
  deposit: {
    title: 'Deposit',
    intro: 'Add simulated money to your wallet. No bank or card is charged.',
    confirmTitle: 'Confirm your deposit',
    confirmButton: 'Confirm deposit',
    busy: 'Depositing…',
    resultTitle: 'Deposit completed',
    again: 'Make another deposit',
  },
  withdrawal: {
    title: 'Withdraw',
    intro: 'Take simulated money out of your wallet. Nothing is paid to a bank.',
    confirmTitle: 'Confirm your withdrawal',
    confirmButton: 'Confirm withdrawal',
    busy: 'Withdrawing…',
    resultTitle: 'Withdrawal completed',
    again: 'Make another withdrawal',
  },
};

/** What the confirm step sends: the canonical amount text and, for a deposit, the note. */
interface Draft {
  amount: string;
  reference?: string;
}

type Step =
  { kind: 'form' } | { kind: 'confirm'; draft: Draft } | { kind: 'done'; result: MoneyMovement };

/**
 * Simulated deposit or withdrawal (doc 02 §2.3; doc 06 MVP-ADR-13): form → confirm → result.
 * The browser checks the amount format only; minimum, limits and balance are the server's
 * (D8). The confirm step owns the Idempotency-Key (doc 02 §1.4, §7 rule 3).
 */
export function MoneyMovementPage({ kind }: { kind: MovementKind }) {
  const text = TEXT[kind];
  const { load, reload, retry } = useWalletOverview();
  const [step, setStep] = useState<Step>({ kind: 'form' });
  const [amountInput, setAmountInput] = useState('');
  const [noteInput, setNoteInput] = useState('');
  // Focus moves to a step's heading only after the customer moved between steps.
  const [moved, setMoved] = useState(false);

  const goTo = (next: Step) => {
    setMoved(true);
    setStep(next);
  };

  return (
    <section aria-labelledby={`${kind}-heading`} className="mx-auto max-w-xl space-y-5">
      <h1 id={`${kind}-heading`} className="text-xl font-semibold">
        {text.title}
      </h1>
      <p className="text-slate-700 dark:text-slate-300">{text.intro}</p>
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
          <section
            aria-labelledby={`${kind}-wallet-heading`}
            className="space-y-2 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
          >
            <h2 id={`${kind}-wallet-heading`} className="text-lg font-medium">
              Your wallet
            </h2>
            <p>
              <span className="font-medium">Available balance:</span>{' '}
              {formatMoney(load.overview.wallet.availableBalance, load.overview.wallet.currency)}
            </p>
            <h3 className="font-medium">{text.title} limits</h3>
            <OperationLimits
              operation={kind}
              limits={load.overview.limits}
              error={load.overview.limitsError}
            />
          </section>
          {step.kind === 'form' && (
            <FormStep
              kind={kind}
              status={load.overview.wallet.status}
              amount={amountInput}
              note={noteInput}
              onAmount={setAmountInput}
              onNote={setNoteInput}
              focusHeading={moved}
              onReview={(draft) => goTo({ kind: 'confirm', draft })}
            />
          )}
          {step.kind === 'confirm' && (
            <ConfirmStep
              kind={kind}
              draft={step.draft}
              onEdit={() => goTo({ kind: 'form' })}
              onDone={(result) => {
                goTo({ kind: 'done', result });
                setAmountInput('');
                setNoteInput('');
                reload();
              }}
              onWalletChanged={reload}
            />
          )}
          {step.kind === 'done' && (
            <ResultStep kind={kind} result={step.result} onAgain={() => goTo({ kind: 'form' })} />
          )}
        </>
      )}
    </section>
  );
}

function FormStep({
  kind,
  status,
  amount,
  note,
  onAmount,
  onNote,
  focusHeading,
  onReview,
}: {
  kind: MovementKind;
  status: string | undefined;
  amount: string;
  note: string;
  onAmount: (value: string) => void;
  onNote: (value: string) => void;
  focusHeading: boolean;
  onReview: (draft: Draft) => void;
}) {
  const formRef = useRef<HTMLFormElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const [errors, setErrors] = useState<{ amount?: string; note?: string }>({});
  useFocusOn(headingRef, focusHeading);

  const blocked = notActiveExplanation(status);
  if (blocked !== null) {
    return (
      <div className="space-y-2 rounded-md border border-amber-400 bg-amber-50 p-3 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
        <p>{blocked}</p>
        <p>
          <Link to="/" className={LINK_CLASSES}>
            Back to your wallet
          </Link>
        </p>
      </div>
    );
  }

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const checked = checkAmount(amount);
    const trimmedNote = note.trim();
    const next = {
      amount: checked.ok ? undefined : checked.error,
      note: kind === 'deposit' ? noteError(trimmedNote) : undefined,
    };
    setErrors(next);
    if (!checked.ok || next.note) {
      focusFirstInvalid();
      return;
    }
    onReview(
      kind === 'deposit' && trimmedNote !== ''
        ? { amount: checked.amount, reference: trimmedNote }
        : { amount: checked.amount },
    );
  };

  return (
    <section aria-labelledby={`${kind}-form-heading`} className="space-y-3">
      <h2
        id={`${kind}-form-heading`}
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-medium focus:outline-none"
      >
        Amount
      </h2>
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
        {kind === 'deposit' && (
          <TextField
            label="Note (optional)"
            name="reference"
            autoComplete="off"
            value={note}
            onChange={onNote}
            maxLength={NOTE_MAX_LENGTH}
            hint={`For your own records, up to ${NOTE_MAX_LENGTH} characters. Our reference number is created when the deposit is made.`}
            error={errors.note}
          />
        )}
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
 * The confirm step. Its Idempotency-Key is created when it mounts and is sent on every attempt
 * from here: a double click is refused while a request is in flight, and a retry after a network
 * error, a 5xx or a 429 reuses the key, so the server runs the movement at most once. After a
 * stored refusal or IDEMPOTENCY_KEY_REUSED the key is renewed for the next attempt; "Edit"
 * unmounts the step, so the next confirm step gets a new key.
 */
function ConfirmStep({
  kind,
  draft,
  onEdit,
  onDone,
  onWalletChanged,
}: {
  kind: MovementKind;
  draft: Draft;
  onEdit: () => void;
  onDone: (result: MoneyMovement) => void;
  onWalletChanged: () => void;
}) {
  const text = TEXT[kind];
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
        const result =
          kind === 'deposit'
            ? await deposit(
                { amount: draft.amount, currency: CURRENCY, reference: draft.reference },
                key,
              )
            : await withdraw({ amount: draft.amount, currency: CURRENCY }, key);
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
          described: describeError(caught),
          code: caught instanceof ApiError ? caught.errorCode : undefined,
        });
      }
    });

  if (kycRequired) {
    return <KycRequiredNotice />;
  }

  const waiting = waitSeconds > 0;
  // A frozen wallet refuses every new key too; only staff can change that (UI only, the server
  // decides). "Edit" leads back to the form, which explains the frozen state.
  const frozen = error?.code === 'WALLET_FROZEN';

  return (
    <section
      aria-labelledby={`${kind}-confirm-heading`}
      className="space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
    >
      <h2
        id={`${kind}-confirm-heading`}
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-semibold focus:outline-none"
      >
        {text.confirmTitle}
      </h2>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
        <dt className="font-medium">Amount</dt>
        <dd>{formatMoney(draft.amount, CURRENCY)}</dd>
        {draft.reference !== undefined && (
          <>
            <dt className="font-medium">Note</dt>
            <dd className="break-words">{draft.reference}</dd>
          </>
        )}
      </dl>
      <p className="text-sm">{SIMULATED_MONEY_NOTE}</p>
      {error && (
        <ErrorNotice error={error.described}>
          {error.code === 'WALLET_FROZEN' && <p className="text-sm">{FROZEN_HINT}</p>}
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
          {busy ? text.busy : waiting ? `Wait ${waitSeconds} s` : text.confirmButton}
        </button>
        <button type="button" onClick={onEdit} disabled={busy} className={SECONDARY_BUTTON_CLASSES}>
          Edit
        </button>
      </div>
    </section>
  );
}

function ResultStep({
  kind,
  result,
  onAgain,
}: {
  kind: MovementKind;
  result: MoneyMovement;
  onAgain: () => void;
}) {
  const text = TEXT[kind];
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, true);

  return (
    <section
      aria-labelledby={`${kind}-result-heading`}
      className="space-y-3 rounded-lg border border-green-600 bg-green-50 p-4 text-green-950 dark:border-green-700 dark:bg-green-950 dark:text-green-100"
    >
      <h2
        id={`${kind}-result-heading`}
        ref={headingRef}
        tabIndex={-1}
        className="text-lg font-semibold focus:outline-none"
      >
        {text.resultTitle}
      </h2>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
        <dt className="font-medium">Amount</dt>
        <dd>{formatMoney(result.amount, CURRENCY)}</dd>
        <dt className="font-medium">Reference</dt>
        <dd>{result.reference ?? '—'}</dd>
        <dt className="font-medium">Status</dt>
        <dd>{movementStatusLabel(result.status)}</dd>
        <dt className="font-medium">Ledger</dt>
        <dd>
          <LedgerStatusChip status={result.ledgerStatus} />
        </dd>
      </dl>
      <p className="text-sm">{SIMULATED_MONEY_NOTE}</p>
      <div className="flex flex-wrap gap-2">
        <Link to="/" className={PRIMARY_BUTTON_CLASSES}>
          Back to your wallet
        </Link>
        <button type="button" onClick={onAgain} className={SECONDARY_BUTTON_CLASSES}>
          {text.again}
        </button>
      </div>
    </section>
  );
}
