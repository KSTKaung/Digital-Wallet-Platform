import { useId, type ReactNode } from 'react';
import { INPUT_CLASSES } from './TextField';

export interface SelectOption<V extends string> {
  value: V;
  label: string;
}

/** A labelled native select; hint and error are linked through aria-describedby. */
export function SelectField<V extends string>({
  label,
  name,
  value,
  onChange,
  options,
  hint,
  error,
  required = false,
}: {
  label: string;
  name: string;
  value: V;
  onChange: (value: V) => void;
  options: readonly SelectOption<V>[];
  hint?: ReactNode;
  error?: string;
  required?: boolean;
}) {
  const selectId = `${name}-${useId()}`;
  const hintId = `${selectId}-hint`;
  const errorId = `${selectId}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className="space-y-1">
      <label htmlFor={selectId} className="block text-sm font-medium">
        {label}
      </label>
      {hint && (
        <p id={hintId} className="text-sm text-slate-600 dark:text-slate-400">
          {hint}
        </p>
      )}
      <select
        id={selectId}
        name={name}
        value={value}
        onChange={(event) => onChange(event.target.value as V)}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={`${INPUT_CLASSES} ${
          error ? 'border-red-600 dark:border-red-400' : 'border-slate-400 dark:border-slate-600'
        }`}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {error && (
        <p id={errorId} className="text-sm font-medium text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
