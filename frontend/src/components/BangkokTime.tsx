import { BANGKOK_TIME_LABEL, formatBangkokDateTime } from '../format/dateTime';

/**
 * An API instant shown in Bangkok time with its label (documentations/08 §4D D6), in a <time>
 * element carrying the original instant. Renders `fallback` when the value is missing.
 */
export function BangkokTime({
  value,
  fallback = '—',
}: {
  value: string | null | undefined;
  fallback?: string;
}) {
  const text = formatBangkokDateTime(value);
  if (text === null) {
    return <>{fallback}</>;
  }
  return (
    <time dateTime={value ?? undefined}>
      {text} <span className="text-slate-600 dark:text-slate-400">({BANGKOK_TIME_LABEL})</span>
    </time>
  );
}
