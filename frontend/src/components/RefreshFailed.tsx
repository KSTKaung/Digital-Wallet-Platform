import { describeError, type ErrorOverrides } from '../api/errorMessages';

/** A failed refresh above data that stays on screen, with the reference for support. */
export function RefreshFailed({
  error,
  what,
  overrides,
}: {
  error: unknown;
  what: string;
  overrides?: ErrorOverrides;
}) {
  const described = describeError(error, overrides);
  return (
    <p className="text-sm font-medium text-red-700 dark:text-red-400">
      The {what} could not be refreshed. {described.message}
      {described.correlationId && ` Reference: ${described.correlationId}`}
    </p>
  );
}
