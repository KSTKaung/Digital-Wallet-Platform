import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { verifyOtp } from '../../api/auth';
import { describeError, type DescribedError } from '../../api/errorMessages';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useFocusOn, useSubmitGuard } from '../../components/formHooks';
import { PasswordField } from '../../components/PasswordField';
import { LINK_CLASSES, PRIMARY_BUTTON_CLASSES, SubmitButton } from '../../components/SubmitButton';
import { TextField } from '../../components/TextField';
import { AuthPage, FORM_CLASSES } from './AuthPage';
import { ResendCode } from './ResendCode';
import {
  collectErrors,
  EMAIL_MAX_LENGTH,
  emailError,
  OTP_HINT,
  OTP_LENGTH,
  otpError,
  RESEND_COOLDOWN_SECONDS,
  requiredError,
} from './validation';
import { clearRegistration, takeRegistration } from './registrationHandoff';

type Field = 'email' | 'password' | 'otp';

/**
 * Account verification (doc 05 §5.1 step 2). Coming from /register, the email and password are
 * taken once from the memory-only hand-off (registrationHandoff.ts) into component state; they
 * never pass through router state or history. A reload or a direct visit finds no hand-off and
 * asks for email, password and code. After success the password is cleared and the user signs
 * in explicitly.
 */
export function VerifyPage() {
  const [handoff] = useState(takeRegistration);
  const fromRegistration = handoff !== null;
  const formRef = useRef<HTMLFormElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [email, setEmail] = useState(handoff?.email ?? '');
  const [password, setPassword] = useState(handoff?.password ?? '');
  const [otp, setOtp] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);
  const [verified, setVerified] = useState(false);
  useFocusOn(headingRef, verified);

  // The hand-off is taken on mount; make sure nothing is left behind when the page goes away.
  useEffect(() => clearRegistration, []);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void run(async () => {
      const errors = collectErrors<Field>({
        email: emailError(email),
        password: requiredError(password, 'password'),
        otp: otpError(otp),
      });
      setFieldErrors(errors ?? {});
      if (errors) {
        setError(null);
        focusFirstInvalid();
        return;
      }
      try {
        await verifyOtp({
          otp: otp.trim(),
          purpose: 'REGISTRATION',
          identifier: email.trim(),
          password,
        });
        clearRegistration();
        setPassword('');
        setOtp('');
        setError(null);
        setVerified(true);
      } catch (caught) {
        setError(describeError(caught));
      }
    });
  };

  if (verified) {
    return (
      <AuthPage id="verify" title="Account verified" headingRef={headingRef}>
        <p className="text-slate-700 dark:text-slate-300">
          Your account is active. Sign in to continue, then complete KYC to get your wallet.
        </p>
        <Link to="/login" className={PRIMARY_BUTTON_CLASSES}>
          Sign in
        </Link>
      </AuthPage>
    );
  }

  const showEmailError = () => {
    setFieldErrors((current) => ({ ...current, email: emailError(email) }));
    focusFirstInvalid();
  };

  return (
    <AuthPage id="verify" title="Verify your account" headingRef={headingRef}>
      <p className="text-slate-700 dark:text-slate-300">
        {fromRegistration
          ? `Enter the ${OTP_LENGTH}-digit code we sent to ${email}.`
          : `Enter your email, your password and the ${OTP_LENGTH}-digit code from our email.`}
      </p>
      {error && <ErrorNotice error={error} />}
      <form ref={formRef} noValidate onSubmit={onSubmit} className={FORM_CLASSES}>
        {!fromRegistration && (
          <>
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
            <PasswordField
              label="Password"
              name="password"
              autoComplete="current-password"
              value={password}
              onChange={setPassword}
              hint="The password you chose when you registered."
              error={fieldErrors.password}
              required
            />
          </>
        )}
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
        <SubmitButton busy={busy} busyText="Verifying…">
          Verify
        </SubmitButton>
      </form>
      <ResendCode
        email={email}
        purpose="REGISTRATION"
        initialCooldown={fromRegistration ? RESEND_COOLDOWN_SECONDS : 0}
        onEmailMissing={showEmailError}
      />
      <p className="text-sm">
        Forgot the password you registered with?{' '}
        <Link to="/register" className={LINK_CLASSES}>
          Register again
        </Link>
        .
      </p>
    </AuthPage>
  );
}
