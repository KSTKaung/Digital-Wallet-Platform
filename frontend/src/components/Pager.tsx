import { SECONDARY_BUTTON_CLASSES } from './SubmitButton';

/**
 * Previous and next buttons with "Page N of M" for a paged list (doc 02 §1.5). `page` is 1-based;
 * `totalPages` is the server's count. Hidden when everything fits on one page.
 */
export function Pager({
  label,
  page,
  totalPages,
  onPage,
}: {
  label: string;
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  if (totalPages <= 1 && page <= 1) {
    return null;
  }
  const last = Math.max(totalPages, 1);
  return (
    <nav aria-label={label} className="flex flex-wrap items-center gap-3">
      <button
        type="button"
        onClick={() => onPage(Math.min(page - 1, last))}
        disabled={page <= 1}
        className={SECONDARY_BUTTON_CLASSES}
      >
        Previous page
      </button>
      <p className="text-sm">
        Page {page} of {last}
      </p>
      <button
        type="button"
        onClick={() => onPage(page + 1)}
        disabled={page >= totalPages}
        className={SECONDARY_BUTTON_CLASSES}
      >
        Next page
      </button>
    </nav>
  );
}
