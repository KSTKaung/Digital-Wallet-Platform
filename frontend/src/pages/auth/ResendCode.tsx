import { useId, useState } from 'react';
import { resendOtp, type OtpPurpose } from '../../api/auth';
import { ApiError } from '../../api/client';
import { describeError, type DescribedError } from '../../api/errorMessages';
import { ErrorNotice, InfoNotice } from '../../components/ErrorNotice';
import { useCountdown, useSubmitGuard } from '../../components/formHooks';
import { SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { RESEND_COOLDOWN_SECONDS } from './validation';

const SENT_MESSAGE =
  'If this email is waiting for a code, we have sent a new one. A new code replaces the old one and expires after 5 minutes.';

/**
 * "Send a new code" with a 60-second countdown. The countdown is client UX only: the server keeps
 * the real cooldown and hourly cap and answers 202 either way (MVP-ADR-23), so the confirmation
 * is neutral. Only the per-IP limit gives 429, whose Retry-After restarts the countdown.
 */
export function ResendCode({
  email,
  purpose,
  initialCooldown,
  onEmailMissing,
}: {
  email: string;
  purpose: OtpPurpose;
  initialCooldown: number;
  /** Called instead of sending when no email has been entered. */
  onEmailMissing: () => void;
}) {
  const { seconds, start } = useCountdown(initialCooldown);
  const { busy, run } = useSubmitGuard();
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<DescribedError | null>(null);
  const waitId = useId();

  const onClick = () =>
    void run(async () => {
      const identifier = email.trim();
      if (identifier === '') {
        onEmailMissing();
        return;
      }
      setError(null);
      setSent(false);
      try {
        await resendOtp({ identifier, purpose });
        setSent(true);
        start(RESEND_COOLDOWN_SECONDS);
      } catch (caught) {
        setError(describeError(caught));
        if (caught instanceof ApiError && caught.status === 429) {
          start(caught.retryAfterSeconds ?? RESEND_COOLDOWN_SECONDS);
        }
      }
    });

  const waiting = seconds > 0;
  return (
    <div className="space-y-2">
      <button
        type="button"
        onClick={onClick}
        disabled={busy || waiting}
        aria-describedby={waiting ? waitId : undefined}
        className={SECONDARY_BUTTON_CLASSES}
      >
        {busy ? 'Sending…' : 'Send a new code'}
      </button>
      {waiting && (
        <p id={waitId} className="text-sm text-slate-600 dark:text-slate-400">
          You can request a new code in {seconds} {seconds === 1 ? 'second' : 'seconds'}.
        </p>
      )}
      {sent && <InfoNotice>{SENT_MESSAGE}</InfoNotice>}
      {error && <ErrorNotice error={error} />}
    </div>
  );
}
