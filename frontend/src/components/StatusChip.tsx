export type ChipTone = 'success' | 'warning' | 'danger' | 'neutral';

const TONE_CLASSES: Readonly<Record<ChipTone, string>> = {
  success:
    'border-green-700 bg-green-50 text-green-900 dark:border-green-500 dark:bg-green-950 dark:text-green-100',
  warning:
    'border-amber-600 bg-amber-50 text-amber-950 dark:border-amber-500 dark:bg-amber-950 dark:text-amber-100',
  danger:
    'border-red-700 bg-red-50 text-red-900 dark:border-red-500 dark:bg-red-950 dark:text-red-100',
  neutral:
    'border-slate-500 bg-slate-100 text-slate-900 dark:border-slate-500 dark:bg-slate-800 dark:text-slate-100',
};

/**
 * A small status label. The meaning is in the text, never in the colour alone (WCAG 1.4.1); the
 * border keeps the chip visible for users who cannot tell the tones apart.
 */
export function StatusChip({ tone, children }: { tone: ChipTone; children: string }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-sm font-medium ${TONE_CLASSES[tone]}`}
    >
      {children}
    </span>
  );
}
