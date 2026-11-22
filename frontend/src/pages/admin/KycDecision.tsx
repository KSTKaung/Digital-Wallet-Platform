import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { approveKycApplication, rejectKycApplication, type KycReviewDetail } from '../../api/admin';
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
import {
  APPROVE_WARNING,
  KYC_APPLICATION_NOT_FOUND_MESSAGE,
  REJECT_REASON_FORMAT_HINT,
  REJECT_REASON_HINT,
  REJECT_WARNING,
  rejectReasonError,
} from './kycReviewText';

export type KycDecisionKind = 'approved' | 'rejected';

type Stage = 'idle' | 'approve' | 'rejectEdit' | 'rejectConfirm';

const DECISION_OVERRIDES: ErrorOverrides = {
  ...STAFF_ERROR_OVERRIDES,
  NOT_FOUND: KYC_APPLICATION_NOT_FOUND_MESSAGE,
};

const PANEL_CLASSES =
  'space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100';

/**
 * Approve or reject a `PENDING_REVIEW` application (doc 02 §2.7; doc 06 MVP-ADR-10). Both ask for
 * confirmation first (Escape or "Cancel" closes and returns focus to the button that opened it),
 * and the confirm button is guarded against a double click. Reject first takes the reason the
 * customer will see; the browser checks its format only, and a `400` naming `reason` (also one
 * that repeats the document reference) is shown on the field. `200` hands the updated detail to
 * `onDecided`; `409 INVALID_KYC_STATE` (decided meanwhile) goes to `onStale`, which reloads.
 */
export function KycDecision({
  id,
  onStart,
  onDecided,
  onStale,
}: {
  id: string;
  /** A decision panel opened: the page clears its previous notice. */
  onStart: () => void;
  onDecided: (result: KycReviewDetail, decision: KycDecisionKind) => void;
  onStale: (error: DescribedError) => void;
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
   * A refused decision: decided meanwhile (409) leaves the panel and reloads; a reason the server
   * refused goes back to the field; anything else stays in the panel, so the officer can retry.
   */
  const handleError = (caught: unknown, rejecting: boolean) => {
    if (caught instanceof ApiError && caught.errorCode === 'INVALID_KYC_STATE') {
      setStage('idle');
      onStale(describeError(caught, STAFF_ERROR_OVERRIDES));
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
        const result = await approveKycApplication(id);
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
        const result = await rejectKycApplication(id, reason);
        setStage('idle');
        onDecided(result, 'rejected');
      } catch (caught) {
        handleError(caught, true);
      }
    });

  const onReview = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const error = rejectReasonError(reason);
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
        aria-labelledby="kyc-approve-heading"
        onKeyDown={onKeyDown}
        className={PANEL_CLASSES}
      >
        <h2
          id="kyc-approve-heading"
          ref={approveHeadingRef}
          tabIndex={-1}
          className="font-semibold focus:outline-none"
        >
          Approve this application?
        </h2>
        <p>{APPROVE_WARNING}</p>
        {panelError && <ErrorNotice error={panelError} />}
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={approve}
            disabled={busy}
            aria-busy={busy}
            className={PRIMARY_BUTTON_CLASSES}
          >
            {busy ? 'Approving…' : 'Yes, approve and create the wallet'}
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
      <section aria-labelledby="kyc-reject-heading" onKeyDown={onKeyDown} className={PANEL_CLASSES}>
        <h2 id="kyc-reject-heading" className="font-semibold">
          Reject this application
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
                <p className="font-medium">{REJECT_REASON_HINT}</p>
                <p>{REJECT_REASON_FORMAT_HINT}</p>
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
      aria-labelledby="kyc-reject-confirm-heading"
      onKeyDown={onKeyDown}
      className={PANEL_CLASSES}
    >
      <h2
        id="kyc-reject-confirm-heading"
        ref={confirmHeadingRef}
        tabIndex={-1}
        className="font-semibold focus:outline-none"
      >
        Reject this application?
      </h2>
      <p>{REJECT_WARNING}</p>
      <figure className="space-y-1">
        <figcaption className="text-sm font-medium">Reason the customer will see</figcaption>
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
          {busy ? 'Rejecting…' : 'Yes, reject the application'}
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
