import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { getProfile, updateProfile, type CustomerProfile } from '../api/customer';
import { describeError, validationErrorField, type DescribedError } from '../api/errorMessages';
import { ErrorNotice, InfoNotice } from '../components/ErrorNotice';
import { useFocusFirstInvalid, useSubmitGuard } from '../components/formHooks';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES, SubmitButton } from '../components/SubmitButton';
import { TextField } from '../components/TextField';
import { countryName } from '../format/country';
import { bangkokToday, formatCalendarDate } from '../format/dateTime';
import { AuthPage, FORM_CLASSES } from './auth/AuthPage';
import { collectErrors } from './auth/validation';

type Field = 'dateOfBirth' | 'countryCode';

export const PROFILE_SAVED_MESSAGE = 'Profile saved.';
export const PROFILE_LOCKED_MESSAGE =
  'Your date of birth and country cannot be changed while your KYC application is under review or after it is approved.';
export const COUNTRY_HINT = 'The 2-letter ISO country code, for example TH for Thailand.';

function dateOfBirthError(value: string, today: string): string | undefined {
  if (value === '') {
    return 'Enter your date of birth.';
  }
  if (formatCalendarDate(value) === null) {
    return 'Enter a real date.';
  }
  // A hint only: the server applies the same rule with its own clock (MVP-ADR-10).
  return value > today ? 'Date of birth cannot be in the future.' : undefined;
}

function countryCodeError(value: string): string | undefined {
  if (value === '') {
    return 'Enter your country code.';
  }
  return /^[A-Z]{2}$/.test(value) ? undefined : 'Enter 2 letters, for example TH.';
}

type Load =
  | { kind: 'loading' }
  | { kind: 'error'; error: DescribedError }
  | { kind: 'loaded'; profile: CustomerProfile };

/**
 * The customer's profile (doc 02 §2.2): masked email and phone, the legal name from an approved
 * KYC application, and the date of birth and country, which KYC needs before a submit. The server
 * decides whether they may change (locked from submit until a rejection, 409 INVALID_KYC_STATE).
 */
export function ProfilePage() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getProfile(controller.signal)
      .then((profile) => setLoad({ kind: 'loaded', profile }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setLoad({ kind: 'error', error: describeError(error) });
        }
      });
    return () => controller.abort();
  }, [attempt]);

  const retry = useCallback(() => {
    setLoad({ kind: 'loading' });
    setAttempt((n) => n + 1);
  }, []);

  return (
    <AuthPage id="profile" title="Profile">
      {load.kind === 'loading' && (
        <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
          Loading your profile…
        </p>
      )}
      {load.kind === 'error' && (
        <ErrorNotice error={load.error}>
          <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
            Try again
          </button>
        </ErrorNotice>
      )}
      {load.kind === 'loaded' && (
        <ProfileDetails
          profile={load.profile}
          onSaved={(profile) => setLoad({ kind: 'loaded', profile })}
        />
      )}
    </AuthPage>
  );
}

function ProfileDetails({
  profile,
  onSaved,
}: {
  profile: CustomerProfile;
  onSaved: (profile: CustomerProfile) => void;
}) {
  const legalName = [profile.firstName, profile.lastName].filter(Boolean).join(' ');
  return (
    <>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-2 rounded-lg border border-slate-200 bg-white p-4 sm:grid-cols-[auto_1fr] dark:border-slate-700 dark:bg-slate-900">
        <dt className="font-medium">Email</dt>
        <dd>{profile.email ?? '—'}</dd>
        <dt className="font-medium">Mobile number</dt>
        <dd>{profile.phoneNumber ?? '—'}</dd>
        <dt className="font-medium">Legal name</dt>
        <dd>{legalName || 'Not set yet. It is taken from your approved KYC application.'}</dd>
        <dt className="font-medium">Date of birth</dt>
        <dd>{formatCalendarDate(profile.dateOfBirth) ?? 'Not set'}</dd>
        <dt className="font-medium">Country</dt>
        <dd>{countryName(profile.countryCode) ?? 'Not set'}</dd>
      </dl>
      <ProfileForm profile={profile} onSaved={onSaved} />
    </>
  );
}

function ProfileForm({
  profile,
  onSaved,
}: {
  profile: CustomerProfile;
  onSaved: (profile: CustomerProfile) => void;
}) {
  const formRef = useRef<HTMLFormElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [dateOfBirth, setDateOfBirth] = useState(profile.dateOfBirth ?? '');
  const [countryCode, setCountryCode] = useState(profile.countryCode ?? '');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);
  const [saved, setSaved] = useState(false);
  const today = bangkokToday();

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void run(async () => {
      setSaved(false);
      const country = countryCode.trim().toUpperCase();
      setCountryCode(country);
      const errors = collectErrors<Field>({
        dateOfBirth: dateOfBirthError(dateOfBirth, today),
        countryCode: countryCodeError(country),
      });
      setFieldErrors(errors ?? {});
      setError(null);
      if (errors) {
        focusFirstInvalid();
        return;
      }
      try {
        const updated = await updateProfile({ dateOfBirth, countryCode: country });
        onSaved(updated);
        setSaved(true);
      } catch (caught) {
        const described = describeError(caught, { INVALID_KYC_STATE: PROFILE_LOCKED_MESSAGE });
        const field = validationErrorField(caught);
        if (field === 'dateOfBirth' || field === 'countryCode') {
          setFieldErrors({ [field]: described.message });
          focusFirstInvalid();
        }
        setError(described);
      }
    });
  };

  return (
    <section aria-labelledby="profile-edit-heading" className="space-y-4">
      <h2 id="profile-edit-heading" className="text-lg font-medium">
        Date of birth and country
      </h2>
      <p className="text-sm text-slate-700 dark:text-slate-300">
        Both are needed before you submit your{' '}
        <Link to="/kyc" className={LINK_CLASSES}>
          KYC application
        </Link>
        . You cannot change them while it is under review or after it is approved.
      </p>
      {saved && <InfoNotice>{PROFILE_SAVED_MESSAGE}</InfoNotice>}
      {error && <ErrorNotice error={error} />}
      <form ref={formRef} noValidate onSubmit={onSubmit} className={FORM_CLASSES}>
        <TextField
          label="Date of birth"
          name="dateOfBirth"
          type="date"
          autoComplete="bday"
          value={dateOfBirth}
          onChange={setDateOfBirth}
          max={today}
          error={fieldErrors.dateOfBirth}
          required
        />
        <TextField
          label="Country code"
          name="countryCode"
          autoComplete="country"
          value={countryCode}
          onChange={setCountryCode}
          maxLength={2}
          hint={COUNTRY_HINT}
          error={fieldErrors.countryCode}
          required
        />
        <SubmitButton busy={busy} busyText="Saving…">
          Save
        </SubmitButton>
      </form>
    </section>
  );
}
