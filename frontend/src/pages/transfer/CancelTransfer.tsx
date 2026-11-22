import { useRef, useState, type KeyboardEvent } from 'react';
import { ApiError } from '../../api/client';
import { describeError, type DescribedError } from '../../api/errorMessages';
import { cancelTransfer, isCancellable, type Transfer } from '../../api/transfers';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn, useSubmitGuard } from '../../components/formHooks';
import { PRIMARY_BUTTON_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { TRANSFER_NOT_FOUND_MESSAGE } from './transferText';

export const CANCEL_WARNING =
  'The transfer will not be sent, and the held amount returns to your available balance. This cannot be undone.';
export const CANCELLED_MESSAGE =
  'The transfer was cancelled. The held amount is back in your available balance.';

type Notice = { kind: 'cancelled' } | { kind: 'error'; error: DescribedError };

/**
 * Cancel for a held transfer the caller sent (doc 02 §2.4 "Cancel"; MVP-ADR-24). Shown only for
 * `SENT` + `PENDING_REVIEW` (UI only: the server decides). Asks for confirmation first (Escape or
 * "Keep the transfer" closes it and returns focus); the button is guarded against a double click.
 * `200` hands the cancelled transfer to `onCancelled`; `409 INVALID_TRANSFER_STATE` (already
 * decided, expired, or a repeat) explains it and asks the page to reload the transfer.
 */
export function CancelTransfer({
  transfer,
  onCancelled,
  onStale,
}: {
  transfer: Transfer;
  onCancelled: (transfer: Transfer) => void;
  onStale: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [returnFocus, setReturnFocus] = useState(0);
  const { busy, run } = useSubmitGuard();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  useFocusOn(headingRef, confirming);
  useFocusOn(buttonRef, returnFocus);
  useFocusOn(doneRef, notice?.kind === 'cancelled');

  const id = transfer.id;
  const cancellable = id !== undefined && isCancellable(transfer);

  const close = () => {
    if (busy) {
      return;
    }
    setConfirming(false);
    setReturnFocus((n) => n + 1);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  };

  const confirm = () =>
    void run(async () => {
      if (id === undefined) {
        return;
      }
      setNotice(null);
      try {
        const result = await cancelTransfer(id);
        setConfirming(false);
        setNotice({ kind: 'cancelled' });
        onCancelled(result);
      } catch (caught) {
        if (caught instanceof ApiError && caught.errorCode === 'INVALID_TRANSFER_STATE') {
          setConfirming(false);
          setNotice({ kind: 'error', error: describeError(caught) });
          onStale();
          return;
        }
        setNotice({
          kind: 'error',
          error: describeError(caught, { NOT_FOUND: TRANSFER_NOT_FOUND_MESSAGE }),
        });
      }
    });

  return (
    <>
      {notice?.kind === 'cancelled' && (
        <p
          ref={doneRef}
          role="status"
          tabIndex={-1}
          className="rounded-md border border-blue-300 bg-blue-50 p-3 font-medium text-blue-950 focus:outline-2 focus:outline-offset-2 focus:outline-blue-600 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-100"
        >
          {CANCELLED_MESSAGE}
        </p>
      )}
      {notice?.kind === 'error' && !confirming && <ErrorNotice error={notice.error} />}
      {cancellable && !confirming && (
        <div>
          <button
            ref={buttonRef}
            type="button"
            onClick={() => {
              setNotice(null);
              setConfirming(true);
            }}
            className={SECONDARY_BUTTON_CLASSES}
          >
            Cancel transfer
          </button>
        </div>
      )}
      {cancellable && confirming && (
        <section
          aria-labelledby="cancel-transfer-heading"
          onKeyDown={onKeyDown}
          className="space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
        >
          <h3
            id="cancel-transfer-heading"
            ref={headingRef}
            tabIndex={-1}
            className="font-semibold focus:outline-none"
          >
            Cancel this transfer?
          </h3>
          <p>{CANCEL_WARNING}</p>
          {notice?.kind === 'error' && <ErrorNotice error={notice.error} />}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={confirm}
              disabled={busy}
              aria-busy={busy}
              className={PRIMARY_BUTTON_CLASSES}
            >
              {busy ? 'Cancelling…' : 'Yes, cancel the transfer'}
            </button>
            <button
              type="button"
              onClick={close}
              disabled={busy}
              className={SECONDARY_BUTTON_CLASSES}
            >
              Keep the transfer
            </button>
          </div>
        </section>
      )}
    </>
  );
}
