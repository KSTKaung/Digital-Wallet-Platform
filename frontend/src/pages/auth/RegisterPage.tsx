import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { register } from '../../api/auth';
import { describeError, type DescribedError } from '../../api/errorMessages';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useSubmitGuard } from '../../components/formHooks';
import { PasswordField } from '../../components/PasswordField';
import { LINK_CLASSES, SubmitButton } from '../../components/SubmitButton';
import { TextField } from '../../components/TextField';
import { AuthPage, FORM_CLASSES } from './AuthPage';
import {
  collectErrors,
  confirmPasswordError,
  EMAIL_MAX_LENGTH,
  emailError,
  newPasswordError,
  PASSWORD_HINT,
  requiredError,
} from './validation';
import { putRegistration } from './registrationHandoff';

type Field = 'email' | 'phoneNumber' | 'password' | 'confirmPassword';

/**
 * Registration (doc 05 §5.1 step 1). The server answers the same 201 for a new and an existing
 * account (MVP-ADR-23), so the page always continues to /verify. verify-otp REGISTRATION needs
 * the password too, so the email and password go to /verify through the memory-only hand-off
 * (registrationHandoff.ts), never through router state, history, the URL or storage.
 */
export function RegisterPage() {
  const navigate = useNavigate();
  const formRef = useRef<HTMLFormElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [email, setEmail] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void run(async () => {
      const errors = collectErrors<Field>({
        email: emailError(email),
        phoneNumber: requiredError(phoneNumber, 'mobile number'),
        password: newPasswordError(password),
        confirmPassword: confirmPasswordError(password, confirmPassword),
      });
      setFieldErrors(errors ?? {});
      if (errors) {
        setError(null);
        focusFirstInvalid();
        return;
      }
      try {
        const trimmedEmail = email.trim();
        await register({ email: trimmedEmail, phoneNumber: phoneNumber.trim(), password });
        putRegistration({ email: trimmedEmail, password });
        navigate('/verify');
      } catch (caught) {
        setError(describeError(caught));
      }
    });
  };

  return (
    <AuthPage id="register" title="Create an account">
      <p className="text-slate-700 dark:text-slate-300">
        We will email you a 6-digit code to verify your account.
      </p>
      {error && <ErrorNotice error={error} />}
      <form ref={formRef} noValidate onSubmit={onSubmit} className={FORM_CLASSES}>
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
        <TextField
          label="Mobile number"
          name="phoneNumber"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          value={phoneNumber}
          onChange={setPhoneNumber}
          hint="International format with country code, for example +66812345678."
          error={fieldErrors.phoneNumber}
          required
        />
        <PasswordField
          label="Password"
          name="password"
          autoComplete="new-password"
          value={password}
          onChange={setPassword}
          hint={PASSWORD_HINT}
          error={fieldErrors.password}
          required
        />
        <PasswordField
          label="Confirm password"
          name="confirmPassword"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={setConfirmPassword}
          error={fieldErrors.confirmPassword}
          required
        />
        <SubmitButton busy={busy} busyText="Creating account…">
          Create account
        </SubmitButton>
      </form>
      <p className="text-sm">
        Already registered?{' '}
        <Link to="/login" className={LINK_CLASSES}>
          Sign in
        </Link>{' '}
        or{' '}
        <Link to="/verify" className={LINK_CLASSES}>
          enter a verification code
        </Link>
        .
      </p>
    </AuthPage>
  );
}
