import { useRef, useState, type FormEvent } from 'react';
import { changePassword, SessionEndedError } from '../../api/auth';
import { ApiError } from '../../api/client';
import { describeError, type DescribedError } from '../../api/errorMessages';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useSubmitGuard } from '../../components/formHooks';
import { PasswordField } from '../../components/PasswordField';
import { SubmitButton } from '../../components/SubmitButton';
import { AuthPage, FORM_CLASSES } from './AuthPage';
import {
  collectErrors,
  confirmPasswordError,
  NEW_PASSWORD_HINT,
  newPasswordError,
  requiredError,
} from './validation';

type Field = 'currentPassword' | 'newPassword' | 'confirmPassword';

export const WRONG_CURRENT_PASSWORD_MESSAGE = 'Current password is incorrect.';

/**
 * Change password (doc 05 §5.2; doc 02 §2.1). A 401 here means a wrong current password, so the
 * client never refreshes on it (client.ts opt-out). Success ends every session on the server;
 * changePassword() then ends the local session with the reason `passwordChanged`, and the
 * session layer (AuthProvider and RequireAuth) sends the user to /login with the notice. The
 * page itself does not navigate, so there is no race with the guard.
 */
export function ChangePasswordPage() {
  const formRef = useRef<HTMLFormElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void run(async () => {
      const errors = collectErrors<Field>({
        currentPassword: requiredError(currentPassword, 'current password'),
        newPassword: newPasswordError(newPassword),
        confirmPassword: confirmPasswordError(newPassword, confirmPassword),
      });
      setFieldErrors(errors ?? {});
      if (errors) {
        setError(null);
        focusFirstInvalid();
        return;
      }
      try {
        await changePassword({ currentPassword, newPassword });
      } catch (caught) {
        if (caught instanceof SessionEndedError) {
          // The session had already ended; AuthProvider has sent the user to /login.
          return;
        }
        setCurrentPassword('');
        if (caught instanceof ApiError && caught.status === 401) {
          setError(describeError(caught, { UNAUTHORIZED: WRONG_CURRENT_PASSWORD_MESSAGE }));
        } else {
          setError(describeError(caught));
        }
      }
    });
  };

  return (
    <AuthPage id="change-password" title="Change password">
      <p className="text-slate-700 dark:text-slate-300">
        Changing your password signs you out everywhere, including this browser.
      </p>
      {error && <ErrorNotice error={error} />}
      <form ref={formRef} noValidate onSubmit={onSubmit} className={FORM_CLASSES}>
        <PasswordField
          label="Current password"
          name="currentPassword"
          autoComplete="current-password"
          value={currentPassword}
          onChange={setCurrentPassword}
          error={fieldErrors.currentPassword}
          required
        />
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
        <SubmitButton busy={busy} busyText="Changing password…">
          Change password
        </SubmitButton>
      </form>
    </AuthPage>
  );
}
