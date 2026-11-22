import { describeError } from '../api/errorMessages';

/** A failed refresh above data that stays on screen, with the reference for support. */
export function RefreshFailed({ error, what }: { error: unknown; what: string }) {
  const described = describeError(error);
  return (
    <p className="text-sm font-medium text-red-700 dark:text-red-400">
      The {what} could not be refreshed. {described.message}
      {described.correlationId && ` Reference: ${described.correlationId}`}
    </p>
  );
}
