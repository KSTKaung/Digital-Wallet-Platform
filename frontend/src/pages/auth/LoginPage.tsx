import { useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { describeError, LOGIN_FAILED_MESSAGE, type DescribedError } from '../../api/errorMessages';
import {
  LOGIN_NOTICE_MESSAGES,
  SESSION_ENDED_MESSAGE,
  useAuth,
  type LoginLocationState,
} from '../../auth/authContext';
import { safeRedirectPath } from '../../auth/redirect';
import { ErrorNotice, InfoNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useSubmitGuard } from '../../components/formHooks';
import { PasswordField } from '../../components/PasswordField';
import { LINK_CLASSES, SubmitButton } from '../../components/SubmitButton';
import { TextField } from '../../components/TextField';
import { AuthPage, FORM_CLASSES } from './AuthPage';
import { collectErrors, EMAIL_MAX_LENGTH, emailError, requiredError } from './validation';

type Field = 'email' | 'password';

function readLoginState(state: unknown): LoginLocationState {
  return typeof state === 'object' && state !== null ? (state as LoginLocationState) : {};
}

/**
 * Sign in (doc 05 §5.1 step 3). Every failed sign-in is the same 401 (wrong password, unknown or
 * locked account, MVP-ADR-23), so the page shows one generic message and never says which.
 * After success it returns to `from` only if that is a safe in-app path.
 */
export function LoginPage() {
  const { status, login } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const routerState = readLoginState(location.state);
  const formRef = useRef<HTMLFormElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);

  const notice = routerState.sessionEnded
    ? SESSION_ENDED_MESSAGE
    : routerState.notice
      ? LOGIN_NOTICE_MESSAGES[routerState.notice]
      : undefined;

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void run(async () => {
      const errors = collectErrors<Field>({
        email: emailError(email),
        password: requiredError(password, 'password'),
      });
      setFieldErrors(errors ?? {});
      if (errors) {
        setError(null);
        focusFirstInvalid();
        return;
      }
      try {
        await login({ email: email.trim(), password });
        setPassword('');
        navigate(safeRedirectPath(routerState.from), { replace: true });
      } catch (caught) {
        setPassword('');
        setError(describeError(caught, { UNAUTHORIZED: LOGIN_FAILED_MESSAGE }));
      }
    });
  };

  if (status === 'signedIn' && !busy) {
    return (
      <AuthPage id="login" title="Sign in">
        <p>
          You are signed in.{' '}
          <Link to="/" className={LINK_CLASSES}>
            Go to the home page
          </Link>
          .
        </p>
      </AuthPage>
    );
  }

  return (
    <AuthPage id="login" title="Sign in">
      {notice && <InfoNotice>{notice}</InfoNotice>}
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
        <PasswordField
          label="Password"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={setPassword}
          error={fieldErrors.password}
          required
        />
        <SubmitButton busy={busy} busyText="Signing in…">
          Sign in
        </SubmitButton>
      </form>
      <ul className="space-y-1 text-sm">
        <li>
          <Link to="/forgot-password" className={LINK_CLASSES}>
            Forgot your password?
          </Link>
        </li>
        <li>
          New here?{' '}
          <Link to="/register" className={LINK_CLASSES}>
            Create an account
          </Link>
        </li>
      </ul>
    </AuthPage>
  );
}
