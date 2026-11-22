import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { forgotPassword, resetPassword, verifyOtp } from '../../api/auth';
import { ApiError } from '../../api/client';
import { describeError, type DescribedError } from '../../api/errorMessages';
import type { LoginLocationState } from '../../auth/authContext';
import { ErrorNotice, InfoNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useFocusOn, useSubmitGuard } from '../../components/formHooks';
import { PasswordField } from '../../components/PasswordField';
import {
  LINK_CLASSES,
  SECONDARY_BUTTON_CLASSES,
  SubmitButton,
} from '../../components/SubmitButton';
import { TextField } from '../../components/TextField';
import { AuthPage, FORM_CLASSES } from './AuthPage';
import { ResendCode } from './ResendCode';
import {
  collectErrors,
  confirmPasswordError,
  EMAIL_MAX_LENGTH,
  emailError,
  NEW_PASSWORD_HINT,
  newPasswordError,
  OTP_HINT,
  OTP_LENGTH,
  otpError,
  RESEND_COOLDOWN_SECONDS,
} from './validation';

export type ResetStep = 'request' | 'code' | 'password';

type Field = 'email' | 'otp' | 'newPassword' | 'confirmPassword';

const TITLES: Record<ResetStep, string> = {
  request: 'Reset your password',
  code: 'Enter your code',
  password: 'Choose a new password',
};

export const CODE_SENT_MESSAGE =
  'If an account exists for this email, we have sent it a 6-digit code. The code expires after 5 minutes.';
export const RESET_EXPIRED_MESSAGE =
  'This password reset has expired or was already used. Start again to get a new code.';

/**
 * Forgot and reset password as one flow on two routes (doc 05 §5.2, MVP-ADR-23):
 * /forgot-password starts by asking for the email; /reset-password starts at the code step (for
 * someone who already has a code). forgot-password always answers 202, so the confirmation never
 * says whether the account exists. The reset token from verify-otp lives only in this
 * component's state and is dropped when the flow ends, restarts or the page is left.
 */
export function PasswordResetPage({ start }: { start: 'request' | 'code' }) {
  const navigate = useNavigate();
  const formRef = useRef<HTMLFormElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [step, setStep] = useState<ResetStep>(start);
  const [stepChanges, setStepChanges] = useState(0);
  const [codeRequested, setCodeRequested] = useState(false);
  const [email, setEmail] = useState('');
  const [otp, setOtp] = useState('');
  const [resetToken, setResetToken] = useState<string | null>(null);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);
  const [expired, setExpired] = useState(false);
  useFocusOn(headingRef, stepChanges);

  const goTo = (next: ResetStep) => {
    setStep(next);
    setStepChanges((n) => n + 1);
    setFieldErrors({});
    setError(null);
  };

  const failValidation = (errors: Partial<Record<Field, string>>) => {
    setFieldErrors(errors);
    setError(null);
    focusFirstInvalid();
  };

  const startAgain = () => {
    setResetToken(null);
    setOtp('');
    setNewPassword('');
    setConfirmPassword('');
    setExpired(false);
    setCodeRequested(false);
    goTo('request');
  };

  const onRequest = () =>
    run(async () => {
      const errors = collectErrors<Field>({ email: emailError(email) });
      if (errors) {
        failValidation(errors);
        return;
      }
      setFieldErrors({});
      try {
        await forgotPassword({ email: email.trim() });
        setCodeRequested(true);
        goTo('code');
      } catch (caught) {
        setError(describeError(caught));
      }
    });

  const onVerify = () =>
    run(async () => {
      const errors = collectErrors<Field>({ email: emailError(email), otp: otpError(otp) });
      if (errors) {
        failValidation(errors);
        return;
      }
      setFieldErrors({});
      try {
        const response = await verifyOtp({
          otp: otp.trim(),
          purpose: 'PASSWORD_RESET',
          identifier: email.trim(),
        });
        const token = response?.resetToken;
        if (typeof token !== 'string' || token === '') {
          throw new ApiError(500, 'SYSTEM_ERROR', 'The reset response could not be read');
        }
        setOtp('');
        setResetToken(token);
        goTo('password');
      } catch (caught) {
        setError(describeError(caught));
      }
    });

  const onReset = () =>
    run(async () => {
      const errors = collectErrors<Field>({
        newPassword: newPasswordError(newPassword),
        confirmPassword: confirmPasswordError(newPassword, confirmPassword),
      });
      if (errors) {
        failValidation(errors);
        return;
      }
      setFieldErrors({});
      if (resetToken === null) {
        setExpired(true);
        setError({ message: RESET_EXPIRED_MESSAGE });
        return;
      }
      try {
        await resetPassword({ resetToken, newPassword });
        setResetToken(null);
        setNewPassword('');
        setConfirmPassword('');
        const state: LoginLocationState = { notice: 'passwordReset' };
        navigate('/login', { replace: true, state });
      } catch (caught) {
        if (isExpiredReset(caught)) {
          setResetToken(null);
          setExpired(true);
          setError({ ...describeError(caught), message: RESET_EXPIRED_MESSAGE });
        } else {
          setError(describeError(caught));
        }
      }
    });

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void (step === 'request' ? onRequest() : step === 'code' ? onVerify() : onReset());
  };

  return (
    <AuthPage id="reset" title={TITLES[step]} headingRef={headingRef}>
      {step === 'request' && (
        <p className="text-slate-700 dark:text-slate-300">
          Enter the email you registered with. We will email you a {OTP_LENGTH}-digit code.
        </p>
      )}
      {step === 'code' && codeRequested && <InfoNotice>{CODE_SENT_MESSAGE}</InfoNotice>}
      {step === 'code' && !codeRequested && (
        <p className="text-slate-700 dark:text-slate-300">
          Enter your email and the {OTP_LENGTH}-digit code from our email.
        </p>
      )}
      {error && (
        <ErrorNotice error={error}>
          {expired && (
            <button type="button" onClick={startAgain} className={SECONDARY_BUTTON_CLASSES}>
              Start again
            </button>
          )}
        </ErrorNotice>
      )}
      <form ref={formRef} noValidate onSubmit={onSubmit} className={FORM_CLASSES}>
        {step !== 'password' && (
          <TextField
            label="Email"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={setEmail}
            maxLength={EMAIL_MAX_LENGTH}
            error={fieldErrors.email}
            required
          />
        )}
        {step === 'code' && (
          <TextField
            label="Verification code"
            name="otp"
            autoComplete="one-time-code"
            inputMode="numeric"
            maxLength={OTP_LENGTH}
            value={otp}
            onChange={setOtp}
            hint={OTP_HINT}
            error={fieldErrors.otp}
            required
          />
        )}
        {step === 'password' && (
          <>
            <PasswordField
              label="New password"
              name="newPassword"
              autoComplete="new-password"
              value={newPassword}
              onChange={setNewPassword}
              hint={NEW_PASSWORD_HINT}
              error={fieldErrors.newPassword}
              required
            />
            <PasswordField
              label="Confirm new password"
              name="confirmPassword"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={setConfirmPassword}
              error={fieldErrors.confirmPassword}
              required
            />
          </>
        )}
        {!expired && (
          <SubmitButton busy={busy} busyText={step === 'password' ? 'Saving…' : 'Please wait…'}>
            {step === 'request' ? 'Send code' : step === 'code' ? 'Continue' : 'Set new password'}
          </SubmitButton>
        )}
      </form>
      {step === 'code' && (
        <ResendCode
          email={email}
          purpose="PASSWORD_RESET"
          initialCooldown={codeRequested ? RESEND_COOLDOWN_SECONDS : 0}
          onEmailMissing={() => failValidation({ email: emailError(email) })}
        />
      )}
      <p className="text-sm">
        <Link to="/login" className={LINK_CLASSES}>
          Back to sign in
        </Link>
      </p>
    </AuthPage>
  );
}

/**
 * reset-password refuses an unknown, used or expired token with 400 VALIDATION_ERROR ("Invalid or
 * expired reset token"); a password-policy refusal is also VALIDATION_ERROR but names
 * `newPassword`. The two are told apart by that field prefix; a 401 is treated as expired too.
 */
function isExpiredReset(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return false;
  }
  if (error.status === 401) {
    return true;
  }
  return error.errorCode === 'VALIDATION_ERROR' && !/^newPassword\b/.test(error.message.trim());
}
