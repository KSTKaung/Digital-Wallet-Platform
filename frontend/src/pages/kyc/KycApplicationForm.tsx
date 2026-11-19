import { useRef, useState, type FormEvent } from 'react';
import {
  describeError,
  validationErrorField,
  type DescribedError,
  type ErrorOverrides,
} from '../../api/errorMessages';
import type { DocumentType, KycApplicationRequest, VerificationLevel } from '../../api/kyc';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusFirstInvalid, useSubmitGuard } from '../../components/formHooks';
import { SelectField } from '../../components/SelectField';
import { SECONDARY_BUTTON_CLASSES, SubmitButton } from '../../components/SubmitButton';
import { TextField } from '../../components/TextField';
import { FORM_CLASSES } from '../auth/AuthPage';
import { collectErrors } from '../auth/validation';
import { DOCUMENT_OPTIONS, LEVEL_OPTIONS } from './kycText';

type Field = 'verificationLevel' | 'documentType' | 'documentReference' | 'firstName' | 'lastName';
const FIELDS: readonly string[] = [
  'verificationLevel',
  'documentType',
  'documentReference',
  'firstName',
  'lastName',
];

/** Column sizes of usr.kyc_applications (doc 06 MVP-ADR-10); the server checks the rest. */
export const DOCUMENT_REFERENCE_MAX_LENGTH = 50;
export const NAME_MAX_LENGTH = 100;

export type KycFormInitial = Partial<Omit<KycApplicationRequest, 'documentReference'>>;

function isLevel(value: string | undefined): value is VerificationLevel {
  return value === 'BASIC' || value === 'FULL';
}

function isDocumentType(value: string | undefined): value is DocumentType {
  return value === 'ID_CARD' || value === 'PASSPORT';
}

function textError(value: string, label: string, max: number): string | undefined {
  if (value === '') {
    return `Enter the ${label}.`;
  }
  return value.length > max ? `Use at most ${max} characters.` : undefined;
}

/**
 * Create or edit form for a KYC application. The document reference is never prefilled: the
 * server returns it masked only, so an edit asks for it again. `save` sends the request and
 * throws the API error; the form shows it (field errors for VALIDATION_ERROR) with `overrides`.
 */
export function KycApplicationForm({
  initial = {},
  editing = false,
  submitLabel,
  busyText,
  overrides,
  save,
  onCancel,
}: {
  initial?: KycFormInitial;
  editing?: boolean;
  submitLabel: string;
  busyText: string;
  overrides?: ErrorOverrides;
  save: (request: KycApplicationRequest) => Promise<void>;
  onCancel?: () => void;
}) {
  const formRef = useRef<HTMLFormElement>(null);
  const focusFirstInvalid = useFocusFirstInvalid(formRef);
  const { busy, run } = useSubmitGuard();
  const [verificationLevel, setVerificationLevel] = useState<VerificationLevel>(
    isLevel(initial.verificationLevel) ? initial.verificationLevel : 'BASIC',
  );
  const [documentType, setDocumentType] = useState<DocumentType>(
    isDocumentType(initial.documentType) ? initial.documentType : 'ID_CARD',
  );
  const [documentReference, setDocumentReference] = useState('');
  const [firstName, setFirstName] = useState(initial.firstName ?? '');
  const [lastName, setLastName] = useState(initial.lastName ?? '');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [error, setError] = useState<DescribedError | null>(null);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void run(async () => {
      const request: KycApplicationRequest = {
        verificationLevel,
        documentType,
        documentReference: documentReference.trim(),
        firstName: firstName.trim(),
        lastName: lastName.trim(),
      };
      const errors = collectErrors<Field>({
        documentReference: textError(
          request.documentReference,
          'document reference',
          DOCUMENT_REFERENCE_MAX_LENGTH,
        ),
        firstName: textError(request.firstName, 'first name', NAME_MAX_LENGTH),
        lastName: textError(request.lastName, 'last name', NAME_MAX_LENGTH),
      });
      setFieldErrors(errors ?? {});
      setError(null);
      if (errors) {
        focusFirstInvalid();
        return;
      }
      try {
        await save(request);
      } catch (caught) {
        const described = describeError(caught, overrides);
        const field = validationErrorField(caught);
        if (field !== undefined && FIELDS.includes(field)) {
          setFieldErrors({ [field]: described.message });
          focusFirstInvalid();
        }
        setError(described);
      }
    });
  };

  return (
    <form ref={formRef} noValidate onSubmit={onSubmit} className={FORM_CLASSES}>
      {error && <ErrorNotice error={error} />}
      <SelectField
        label="Verification level"
        name="verificationLevel"
        value={verificationLevel}
        onChange={setVerificationLevel}
        options={LEVEL_OPTIONS}
        hint="The level sets your wallet limits. Both levels need only a document type and reference: no upload and no selfie."
        error={fieldErrors.verificationLevel}
        required
      />
      <SelectField
        label="Document type"
        name="documentType"
        value={documentType}
        onChange={setDocumentType}
        options={DOCUMENT_OPTIONS}
        error={fieldErrors.documentType}
        required
      />
      <TextField
        label="Document reference"
        name="documentReference"
        autoComplete="off"
        value={documentReference}
        onChange={setDocumentReference}
        maxLength={DOCUMENT_REFERENCE_MAX_LENGTH}
        hint={
          editing
            ? `Enter the reference again: it is only shown masked after saving. Up to ${DOCUMENT_REFERENCE_MAX_LENGTH} characters.`
            : `The number on your document, up to ${DOCUMENT_REFERENCE_MAX_LENGTH} characters. It is only shown masked after saving.`
        }
        error={fieldErrors.documentReference}
        required
      />
      <TextField
        label="First name"
        name="firstName"
        autoComplete="given-name"
        value={firstName}
        onChange={setFirstName}
        maxLength={NAME_MAX_LENGTH}
        hint="As on your document."
        error={fieldErrors.firstName}
        required
      />
      <TextField
        label="Last name"
        name="lastName"
        autoComplete="family-name"
        value={lastName}
        onChange={setLastName}
        maxLength={NAME_MAX_LENGTH}
        error={fieldErrors.lastName}
        required
      />
      <div className="flex flex-wrap gap-2">
        <SubmitButton busy={busy} busyText={busyText}>
          {submitLabel}
        </SubmitButton>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className={SECONDARY_BUTTON_CLASSES}
          >
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}
