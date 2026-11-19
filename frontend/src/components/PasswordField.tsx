import { useId, useState } from 'react';
import { TextField, type TextFieldProps } from './TextField';

export type PasswordFieldProps = Omit<TextFieldProps, 'type' | 'trailing' | 'inputMode' | 'id'> & {
  autoComplete: 'current-password' | 'new-password';
};

/** A password input with a show/hide toggle. The value is never logged or put in the URL. */
export function PasswordField(props: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  const inputId = `${props.name}-${useId()}`;

  return (
    <TextField
      {...props}
      id={inputId}
      type={visible ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={`${visible ? 'Hide' : 'Show'} ${props.label.toLowerCase()}`}
          aria-controls={inputId}
          className="shrink-0 rounded-md border border-slate-400 px-3 py-2 text-sm font-medium text-slate-800 hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          {visible ? 'Hide' : 'Show'}
        </button>
      }
    />
  );
}
