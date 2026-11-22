import { useEffect, useMemo, useRef, type ReactNode } from 'react';
import { describeError, type DescribedError } from '../api/errorMessages';

/**
 * Error summary for a failed request: announced (role="alert") and focused when it appears or
 * changes, with the correlation id as a reference for support (doc 02 §1.7). It refocuses when the
 * `error` object changes, so pass a stable object (state, a constant, or LoadErrorNotice), never
 * one built during render, or every re-render steals the focus.
 */
export function ErrorNotice({ error, children }: { error: DescribedError; children?: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ref.current?.focus();
  }, [error]);

  return (
    <div
      ref={ref}
      role="alert"
      tabIndex={-1}
      className="space-y-1 rounded-md border border-red-300 bg-red-50 p-3 text-red-900 focus:outline-2 focus:outline-offset-2 focus:outline-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-100"
    >
      <p className="font-medium">{error.message}</p>
      {error.correlationId && <p className="text-sm">Reference: {error.correlationId}</p>}
      {children}
    </div>
  );
}

/**
 * ErrorNotice for a raw error kept in state (a failed load): the description is memoised on the
 * error, so a re-render with the same error keeps the focus where the user put it.
 */
export function LoadErrorNotice({ error, children }: { error: unknown; children?: ReactNode }) {
  const described = useMemo(() => describeError(error), [error]);
  return <ErrorNotice error={described}>{children}</ErrorNotice>;
}

/** A neutral or success message (role="status", announced politely). */
export function InfoNotice({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="rounded-md border border-blue-300 bg-blue-50 p-3 text-blue-950 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-100"
    >
      {children}
    </div>
  );
}
