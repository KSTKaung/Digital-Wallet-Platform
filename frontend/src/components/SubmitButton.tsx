import type { ReactNode } from 'react';

export const PRIMARY_BUTTON_CLASSES =
  'inline-flex items-center justify-center rounded-md bg-blue-700 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 disabled:cursor-not-allowed disabled:bg-slate-500';

export const SECONDARY_BUTTON_CLASSES =
  'inline-flex items-center justify-center rounded-md border border-slate-400 px-4 py-2 text-sm font-medium text-slate-800 hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 disabled:cursor-not-allowed disabled:text-slate-500 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-800';

export const LINK_CLASSES =
  'rounded text-blue-700 underline underline-offset-4 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 dark:text-blue-400 dark:hover:text-blue-300';

/**
 * Form submit button. While `busy` it is disabled and shows `busyText`; the form's submit handler
 * (useSubmitGuard) also refuses a second submit, so a double click sends one request.
 */
export function SubmitButton({
  busy,
  busyText,
  children,
}: {
  busy: boolean;
  busyText: string;
  children: ReactNode;
}) {
  return (
    <button type="submit" disabled={busy} aria-busy={busy} className={PRIMARY_BUTTON_CLASSES}>
      {busy ? busyText : children}
    </button>
  );
}
