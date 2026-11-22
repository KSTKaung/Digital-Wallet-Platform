import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  freezeCustomerWallet,
  unfreezeCustomerWallet,
  type CustomerWalletStatus,
} from '../../api/admin';
import { ApiError } from '../../api/client';
import {
  describeError,
  STAFF_ERROR_OVERRIDES,
  validationErrorField,
  type DescribedError,
  type ErrorOverrides,
} from '../../api/errorMessages';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useFocusOn, useSubmitGuard } from '../../components/formHooks';
import { PRIMARY_BUTTON_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { TextAreaField } from '../../components/TextAreaField';
import { TextField } from '../../components/TextField';
import {
  BUSY_BUTTONS,
  CONFIRM_BUTTONS,
  CONFIRM_HEADINGS,
  CONFIRM_REPEAT_NOTES,
  CONFIRM_WARNINGS,
  CUSTOMER_ID_HINT,
  CUSTOMER_WALLET_INTRO,
  CUSTOMER_WALLET_NOT_FOUND_MESSAGE,
  customerIdError,
  NO_STATUS_READ_NOTE,
  parseCustomerId,
  resultMessage,
  WALLET_STATUS_REASON_FORMAT_HINT,
  WALLET_STATUS_REASON_HINT,
  walletStatusReasonError,
  type WalletStatusAction,
} from './customerWalletText';

const OVERRIDES: ErrorOverrides = {
  ...STAFF_ERROR_OVERRIDES,
  NOT_FOUND: CUSTOMER_WALLET_NOT_FOUND_MESSAGE,
};

const PANEL_CLASSES =
  'space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100';

const QUOTE_CLASSES =
  'rounded-md border border-amber-400 bg-white p-3 break-words whitespace-pre-wrap text-slate-900 dark:border-amber-700 dark:bg-slate-900 dark:text-slate-100';

interface Result {
  action: WalletStatusAction;
  status: string | undefined;
}

interface Confirming {
  action: WalletStatusAction;
  /** The id as sent: trimmed and lower case. */
  customerId: string;
}

/**
 * `/admin/customers` (ADMIN): freeze or unfreeze a customer's wallet by a typed customer id and a
 * required reason (doc 02 §2.7; documentations/08 §4D D5, AC 13). There is no customer search and
 * no read of a customer's wallet status, so the screen shows only the status a `200` returns. The
 * id stays in component state: never in the URL, in storage or in the console.
 *
 * Each action asks for confirmation (Escape or "Cancel" returns focus to the button that opened
 * it); the confirm button is guarded against a double click. No Idempotency-Key: a repeat is
 * `200` with the current status. The browser checks the id and the reason format only. A `400`
 * naming a field goes back to that field; the `404` (unknown customer or no wallet, one text) goes
 * back to the form; anything else stays in the panel so the admin can retry. Typed values are kept
 * after an error; after a `200` the reason is cleared and the id kept.
 */
export function CustomerWalletPage() {
  const [customerId, setCustomerId] = useState('');
  const [reason, setReason] = useState('');
  const [idError, setIdError] = useState<string | undefined>(undefined);
  const [reasonError, setReasonError] = useState<string | undefined>(undefined);
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [panelError, setPanelError] = useState<DescribedError | null>(null);
  const [formError, setFormError] = useState<DescribedError | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [returnTo, setReturnTo] = useState<{ action: WalletStatusAction; n: number } | null>(null);
  const { busy, run } = useSubmitGuard();

  const formRef = useRef<HTMLFormElement>(null);
  const freezeButtonRef = useRef<HTMLButtonElement>(null);
  const unfreezeButtonRef = useRef<HTMLButtonElement>(null);
  const confirmHeadingRef = useRef<HTMLHeadingElement>(null);
  const resultRef = useRef<HTMLParagraphElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);

  useFocusOn(confirmHeadingRef, confirming);
  useFocusOn(resultRef, result);
  useFocusOn(freezeButtonRef, returnTo?.action === 'freeze' ? returnTo : null);
  useFocusOn(unfreezeButtonRef, returnTo?.action === 'unfreeze' ? returnTo : null);

  const review = (action: WalletStatusAction) => {
    const nextIdError = customerIdError(customerId);
    const nextReasonError = walletStatusReasonError(reason);
    setIdError(nextIdError);
    setReasonError(nextReasonError);
    setFormError(null);
    setPanelError(null);
    setResult(null);
    const parsed = parseCustomerId(customerId);
    if (nextIdError || nextReasonError || parsed === undefined) {
      focusFirstInvalid();
      return;
    }
    setConfirming({ action, customerId: parsed });
  };

  const close = () => {
    if (busy || !confirming) {
      return;
    }
    const { action } = confirming;
    setConfirming(null);
    setPanelError(null);
    setReturnTo((previous) => ({ action, n: (previous?.n ?? 0) + 1 }));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  };

  const handleError = (caught: unknown) => {
    const field = validationErrorField(caught);
    if (field === 'reason' || field === 'customerId') {
      const message = describeError(caught).message;
      if (field === 'reason') {
        setReasonError(message);
      } else {
        setIdError(message);
      }
      setConfirming(null);
      focusFirstInvalid();
      return;
    }
    if (caught instanceof ApiError && caught.status === 404) {
      setConfirming(null);
      setFormError(describeError(caught, OVERRIDES));
      return;
    }
    setPanelError(describeError(caught, OVERRIDES));
  };

  const confirm = () =>
    void run(async () => {
      if (!confirming) {
        return;
      }
      const { action, customerId: id } = confirming;
      setPanelError(null);
      try {
        const response: CustomerWalletStatus =
          action === 'freeze'
            ? await freezeCustomerWallet(id, reason)
            : await unfreezeCustomerWallet(id, reason);
        setConfirming(null);
        setReason('');
        setResult({ action, status: response.status });
      } catch (caught) {
        handleError(caught);
      }
    });

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    // Enter in the id field submits the form; neither action starts without its own button.
    event.preventDefault();
  };

  return (
    <section aria-labelledby="customer-wallet-heading" className="mx-auto max-w-2xl space-y-4">
      <h1 id="customer-wallet-heading" className="text-xl font-semibold">
        Wallet freeze
      </h1>
      <p>{CUSTOMER_WALLET_INTRO}</p>
      <p className="text-sm text-slate-700 dark:text-slate-300">{NO_STATUS_READ_NOTE}</p>

      {result && (
        <p
          ref={resultRef}
          role="status"
          tabIndex={-1}
          className="rounded-md border border-blue-300 bg-blue-50 p-3 font-medium text-blue-950 focus:outline-2 focus:outline-offset-2 focus:outline-blue-600 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-100"
        >
          {resultMessage(result.action, result.status)}
        </p>
      )}

      {confirming ? (
        <section
          aria-labelledby="wallet-status-confirm-heading"
          onKeyDown={onKeyDown}
          className={PANEL_CLASSES}
        >
          <h2
            id="wallet-status-confirm-heading"
            ref={confirmHeadingRef}
            tabIndex={-1}
            className="font-semibold focus:outline-none"
          >
            {CONFIRM_HEADINGS[confirming.action]}
          </h2>
          <p>{CONFIRM_WARNINGS[confirming.action]}</p>
          <p>{CONFIRM_REPEAT_NOTES[confirming.action]}</p>
          <dl className="space-y-2 text-sm">
            <div>
              <dt className="font-medium">Customer ID</dt>
              <dd className="font-mono break-all">{confirming.customerId}</dd>
            </div>
            <div>
              <dt className="font-medium">Your reason (staff only)</dt>
              <dd className={QUOTE_CLASSES}>{reason}</dd>
            </div>
          </dl>
          {panelError && <ErrorNotice error={panelError} />}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={confirm}
              disabled={busy}
              aria-busy={busy}
              className={PRIMARY_BUTTON_CLASSES}
            >
              {busy ? BUSY_BUTTONS[confirming.action] : CONFIRM_BUTTONS[confirming.action]}
            </button>
            <button
              type="button"
              onClick={close}
              disabled={busy}
              className={SECONDARY_BUTTON_CLASSES}
            >
              Cancel
            </button>
          </div>
        </section>
      ) : (
        <form ref={formRef} noValidate onSubmit={onSubmit} className="space-y-4">
          {formError && <ErrorNotice error={formError} />}
          <TextField
            label="Customer ID"
            name="customerId"
            value={customerId}
            onChange={setCustomerId}
            autoComplete="off"
            hint={CUSTOMER_ID_HINT}
            error={idError}
            required
          />
          <TextAreaField
            label="Reason"
            name="reason"
            value={reason}
            onChange={setReason}
            hint={
              <>
                <p className="font-medium">{WALLET_STATUS_REASON_HINT}</p>
                <p>{WALLET_STATUS_REASON_FORMAT_HINT}</p>
              </>
            }
            error={reasonError}
            required
          />
          <div className="flex flex-wrap gap-2">
            <button
              ref={freezeButtonRef}
              type="button"
              onClick={() => review('freeze')}
              className={PRIMARY_BUTTON_CLASSES}
            >
              Freeze wallet
            </button>
            <button
              ref={unfreezeButtonRef}
              type="button"
              onClick={() => review('unfreeze')}
              className={SECONDARY_BUTTON_CLASSES}
            >
              Unfreeze wallet
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
