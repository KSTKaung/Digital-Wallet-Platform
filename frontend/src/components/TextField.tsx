import { useId, type HTMLAttributes, type ReactNode, type Ref } from 'react';

export interface TextFieldProps {
  label: string;
  name: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'email' | 'tel' | 'password';
  autoComplete: string;
  /** Shown under the label and linked through aria-describedby. */
  hint?: ReactNode;
  /** Field error; marks the input invalid and is linked through aria-describedby. */
  error?: string;
  required?: boolean;
  inputMode?: HTMLAttributes<HTMLInputElement>['inputMode'];
  maxLength?: number;
  readOnly?: boolean;
  ref?: Ref<HTMLInputElement>;
  /** Rendered after the input (e.g. a show-password toggle). */
  trailing?: ReactNode;
  /** Id of the input, for controls that point at it (aria-controls). */
  id?: string;
}

export const INPUT_CLASSES =
  'block w-full rounded-md border bg-white px-3 py-2 text-base text-slate-900 shadow-sm focus:outline-2 focus:outline-offset-1 focus:outline-blue-600 dark:bg-slate-900 dark:text-slate-100';

/** A labelled input. The label, hint and error are all programmatically associated. */
export function TextField({
  label,
  name,
  value,
  onChange,
  type = 'text',
  autoComplete,
  hint,
  error,
  required = false,
  inputMode,
  maxLength,
  readOnly,
  ref,
  trailing,
  id,
}: TextFieldProps) {
  const generatedId = useId();
  const inputId = id ?? `${name}-${generatedId}`;
  const hintId = `${inputId}-hint`;
  const errorId = `${inputId}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className="space-y-1">
      <label htmlFor={inputId} className="block text-sm font-medium">
        {label}
      </label>
      {hint && (
        <p id={hintId} className="text-sm text-slate-600 dark:text-slate-400">
          {hint}
        </p>
      )}
      <div className="flex gap-2">
        <input
          ref={ref}
          id={inputId}
          name={name}
          type={type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete={autoComplete}
          inputMode={inputMode}
          maxLength={maxLength}
          readOnly={readOnly}
          required={required}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy || undefined}
          className={`${INPUT_CLASSES} ${
            error ? 'border-red-600 dark:border-red-400' : 'border-slate-400 dark:border-slate-600'
          }`}
        />
        {trailing}
      </div>
      {error && (
        <p id={errorId} className="text-sm font-medium text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
