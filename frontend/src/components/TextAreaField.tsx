import { useId, type ReactNode, type Ref } from 'react';
import { INPUT_CLASSES } from './TextField';

/** A labelled textarea. The label, hint and error are all programmatically associated. */
export function TextAreaField({
  label,
  name,
  value,
  onChange,
  hint,
  error,
  required = false,
  rows = 3,
  ref,
}: {
  label: string;
  name: string;
  value: string;
  onChange: (value: string) => void;
  /** Shown under the label and linked through aria-describedby. */
  hint?: ReactNode;
  /** Field error; marks the textarea invalid and is linked through aria-describedby. */
  error?: string;
  required?: boolean;
  rows?: number;
  ref?: Ref<HTMLTextAreaElement>;
}) {
  const fieldId = `${name}-${useId()}`;
  const hintId = `${fieldId}-hint`;
  const errorId = `${fieldId}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className="space-y-1">
      <label htmlFor={fieldId} className="block text-sm font-medium">
        {label}
      </label>
      {hint && (
        <div id={hintId} className="text-sm text-slate-700 dark:text-slate-300">
          {hint}
        </div>
      )}
      <textarea
        ref={ref}
        id={fieldId}
        name={name}
        value={value}
        rows={rows}
        onChange={(event) => onChange(event.target.value)}
        autoComplete="off"
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={`${INPUT_CLASSES} ${
          error ? 'border-red-600 dark:border-red-400' : 'border-slate-400 dark:border-slate-600'
        }`}
      />
      {error && (
        <p id={errorId} className="text-sm font-medium text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
