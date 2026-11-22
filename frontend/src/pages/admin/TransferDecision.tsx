import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  approveTransferReview,
  rejectTransferReview,
  type TransferReviewDetail,
} from '../../api/admin';
import { ApiError } from '../../api/client';
import {
  describeError,
  TRANSFER_DECISION_ERROR_OVERRIDES,
  validationErrorField,
  type DescribedError,
  type ErrorOverrides,
} from '../../api/errorMessages';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useFocusOn, useSubmitGuard } from '../../components/formHooks';
import { PRIMARY_BUTTON_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { TextAreaField } from '../../components/TextAreaField';
import {
  APPROVE_TRANSFER_WARNING,
  REJECT_TRANSFER_REASON_FORMAT_HINT,
  REJECT_TRANSFER_REASON_HINT,
  REJECT_TRANSFER_WARNING,
  rejectTransferReasonError,
  TRANSFER_REVIEW_NOT_FOUND_MESSAGE,
} from './transferReviewText';

export type TransferDecisionKind = 'approved' | 'rejected';

/** Why a decision was refused in a way the page shows outside the panel. */
export type RefusalKind = 'stale' | 'party';

type Stage = 'idle' | 'approve' | 'rejectEdit' | 'rejectConfirm';

const DECISION_OVERRIDES: ErrorOverrides = {
  ...TRANSFER_DECISION_ERROR_OVERRIDES,
  NOT_FOUND: TRANSFER_REVIEW_NOT_FOUND_MESSAGE,
};

const PANEL_CLASSES =
  'space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100';

/**
 * Approve or reject a `PENDING_REVIEW` transfer (doc 02 §2.7; doc 06 MVP-ADR-24), as KycDecision
 * does for KYC: both ask for confirmation first (Escape or "Cancel" closes and returns focus to
 * the button that opened it), and the confirm button is guarded against a double click. No
 * Idempotency-Key: a repeat finds the transfer decided and gets `409`. Reject first takes the
 * staff-only reason, which the server requires; the browser checks its format only, and a `400`
 * naming `reason` is shown on the field. `200` hands the detail to `onDecided`; `409
 * INVALID_TRANSFER_STATE` (decided meanwhile, or expired by a late decision) and `403` (the
 * reviewer takes part in the transfer; nothing changed) close the panel and go to `onRefused`.
 */
export function TransferDecision({
  transferId,
  onStart,
  onDecided,
  onRefused,
}: {
  transferId: string;
  /** A decision panel opened: the page clears its previous notice. */
  onStart: () => void;
  onDecided: (result: TransferReviewDetail, decision: TransferDecisionKind) => void;
  onRefused: (error: DescribedError, kind: RefusalKind) => void;
}) {
  const [stage, setStage] = useState<Stage>('idle');
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | undefined>(undefined);
  const [panelError, setPanelError] = useState<DescribedError | null>(null);
  const [returnTo, setReturnTo] = useState<{ button: 'approve' | 'reject'; n: number } | null>(
    null,
  );
  const { busy, run } = useSubmitGuard();

  const approveButtonRef = useRef<HTMLButtonElement>(null);
  const rejectButtonRef = useRef<HTMLButtonElement>(null);
  const approveHeadingRef = useRef<HTMLHeadingElement>(null);
  const confirmHeadingRef = useRef<HTMLHeadingElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const [focusReason, setFocusReason] = useState(0);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);

  useFocusOn(approveHeadingRef, stage === 'approve');
  useFocusOn(confirmHeadingRef, stage === 'rejectConfirm');
  useFocusOn(reasonRef, focusReason);
  useFocusOn(approveButtonRef, returnTo?.button === 'approve' ? returnTo : null);
  useFocusOn(rejectButtonRef, returnTo?.button === 'reject' ? returnTo : null);

  const open = (next: 'approve' | 'rejectEdit') => {
    onStart();
    setPanelError(null);
    setReasonError(undefined);
    setStage(next);
    if (next === 'rejectEdit') {
      setFocusReason((n) => n + 1);
    }
  };

  const close = () => {
    if (busy) {
      return;
    }
    const button = stage === 'approve' ? 'approve' : 'reject';
    setStage('idle');
    setPanelError(null);
    setReturnTo((previous) => ({ button, n: (previous?.n ?? 0) + 1 }));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  };

  /**
   * A refused decision: decided meanwhile or expired (409) and a party reviewer (403) leave the
   * panel; a reason the server refused goes back to the field; anything else stays in the panel,
   * so the officer can retry.
   */
  const handleError = (caught: unknown, rejecting: boolean) => {
    if (caught instanceof ApiError && caught.errorCode === 'INVALID_TRANSFER_STATE') {
      setStage('idle');
      onRefused(describeError(caught, DECISION_OVERRIDES), 'stale');
      return;
    }
    if (caught instanceof ApiError && caught.status === 403) {
      setStage('idle');
      onRefused(describeError(caught, DECISION_OVERRIDES), 'party');
      return;
    }
    if (rejecting && validationErrorField(caught) === 'reason') {
      setStage('rejectEdit');
      setReasonError(describeError(caught).message);
      focusFirstInvalid();
      return;
    }
    setPanelError(describeError(caught, DECISION_OVERRIDES));
  };

  const approve = () =>
    void run(async () => {
      setPanelError(null);
      try {
        const result = await approveTransferReview(transferId);
        setStage('idle');
        onDecided(result, 'approved');
      } catch (caught) {
        handleError(caught, false);
      }
    });

  const reject = () =>
    void run(async () => {
      setPanelError(null);
      try {
        const result = await rejectTransferReview(transferId, reason);
        setStage('idle');
        onDecided(result, 'rejected');
      } catch (caught) {
        handleError(caught, true);
      }
    });

  const onReview = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const error = rejectTransferReasonError(reason);
    setReasonError(error);
    setPanelError(null);
    if (error) {
      focusFirstInvalid();
      return;
    }
    setStage('rejectConfirm');
  };

  if (stage === 'idle') {
    return (
      <div className="flex flex-wrap gap-2">
        <button
          ref={approveButtonRef}
          type="button"
          onClick={() => open('approve')}
          className={PRIMARY_BUTTON_CLASSES}
        >
          Approve
        </button>
        <button
          ref={rejectButtonRef}
          type="button"
          onClick={() => open('rejectEdit')}
          className={SECONDARY_BUTTON_CLASSES}
        >
          Reject
        </button>
      </div>
    );
  }

  if (stage === 'approve') {
    return (
      <section
        aria-labelledby="transfer-approve-heading"
        onKeyDown={onKeyDown}
        className={PANEL_CLASSES}
      >
        <h2
          id="transfer-approve-heading"
          ref={approveHeadingRef}
          tabIndex={-1}
          className="font-semibold focus:outline-none"
        >
          Approve this transfer?
        </h2>
        <p>{APPROVE_TRANSFER_WARNING}</p>
        {panelError && <ErrorNotice error={panelError} />}
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={approve}
            disabled={busy}
            aria-busy={busy}
            className={PRIMARY_BUTTON_CLASSES}
          >
            {busy ? 'Approving…' : 'Yes, approve and send the money'}
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
    );
  }

  if (stage === 'rejectEdit') {
    return (
      <section
        aria-labelledby="transfer-reject-heading"
        onKeyDown={onKeyDown}
        className={PANEL_CLASSES}
      >
        <h2 id="transfer-reject-heading" className="font-semibold">
          Reject this transfer
        </h2>
        <form ref={formRef} noValidate onSubmit={onReview} className="space-y-3">
          <TextAreaField
            ref={reasonRef}
            label="Reason"
            name="reason"
            value={reason}
            onChange={setReason}
            hint={
              <>
                <p className="font-medium">{REJECT_TRANSFER_REASON_HINT}</p>
                <p>{REJECT_TRANSFER_REASON_FORMAT_HINT}</p>
              </>
            }
            error={reasonError}
            required
          />
          <div className="flex flex-wrap gap-2">
            <button type="submit" className={PRIMARY_BUTTON_CLASSES}>
              Continue
            </button>
            <button type="button" onClick={close} className={SECONDARY_BUTTON_CLASSES}>
              Cancel
            </button>
          </div>
        </form>
      </section>
    );
  }

  return (
    <section
      aria-labelledby="transfer-reject-confirm-heading"
      onKeyDown={onKeyDown}
      className={PANEL_CLASSES}
    >
      <h2
        id="transfer-reject-confirm-heading"
        ref={confirmHeadingRef}
        tabIndex={-1}
        className="font-semibold focus:outline-none"
      >
        Reject this transfer?
      </h2>
      <p>{REJECT_TRANSFER_WARNING}</p>
      <figure className="space-y-1">
        <figcaption className="text-sm font-medium">Your reason (staff only)</figcaption>
        <blockquote className="rounded-md border border-amber-400 bg-white p-3 break-words whitespace-pre-wrap text-slate-900 dark:border-amber-700 dark:bg-slate-900 dark:text-slate-100">
          {reason}
        </blockquote>
      </figure>
      {panelError && <ErrorNotice error={panelError} />}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={reject}
          disabled={busy}
          aria-busy={busy}
          className={PRIMARY_BUTTON_CLASSES}
        >
          {busy ? 'Rejecting…' : 'Yes, reject and return the money'}
        </button>
        <button
          type="button"
          onClick={() => {
            setPanelError(null);
            setStage('rejectEdit');
            setFocusReason((n) => n + 1);
          }}
          disabled={busy}
          className={SECONDARY_BUTTON_CLASSES}
        >
          Change the reason
        </button>
        <button type="button" onClick={close} disabled={busy} className={SECONDARY_BUTTON_CLASSES}>
          Cancel
        </button>
      </div>
    </section>
  );
}
