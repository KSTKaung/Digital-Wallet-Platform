import { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router';
import { listTransferReviews, type TransferReviewSummary } from '../../api/admin';
import { STAFF_ERROR_OVERRIDES } from '../../api/errorMessages';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad, usePageParam } from '../../components/dataHooks';
import { LoadErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn } from '../../components/formHooks';
import { Pager } from '../../components/Pager';
import { RefreshFailed } from '../../components/RefreshFailed';
import { StatusChip } from '../../components/StatusChip';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { formatMoney } from '../../format/money';
import {
  EMPTY_TRANSFER_QUEUE_MESSAGE,
  isOverdue,
  OVERDUE_LABEL,
  QUEUE_OVERDUE_NOTE,
  QUEUE_PAGING_NOTE,
} from './transferReviewText';

/**
 * `/admin/transfer-reviews` (COMPLIANCE_OFFICER): transfers held for review, soonest deadline
 * first (doc 02 §2.7). Only `PENDING_REVIEW` is listed, overdue ones included until expired. The
 * page lives in the URL; refreshed by a button and on window focus (D7), never polled.
 */
export function TransferReviewQueuePage() {
  const [page, setPage] = usePageParam();
  const [moved, setMoved] = useState(false);

  return (
    <section aria-labelledby="transfer-queue-heading" className="space-y-4">
      <h1 id="transfer-queue-heading" className="text-xl font-semibold">
        Transfer reviews
      </h1>
      <div className="space-y-1 text-sm text-slate-700 dark:text-slate-300">
        <p>{QUEUE_PAGING_NOTE}</p>
        <p>{QUEUE_OVERDUE_NOTE}</p>
      </div>
      <TransferQueue
        key={page}
        page={page}
        focusHeading={moved}
        onPage={(next) => {
          setMoved(true);
          setPage(next);
        }}
      />
    </section>
  );
}

function TransferQueue({
  page,
  focusHeading,
  onPage,
}: {
  page: number;
  focusHeading: boolean;
  onPage: (page: number) => void;
}) {
  // The time the answer arrived, for the overdue marker (a load, a refresh or a focus refetch;
  // nothing is polled, and the render stays pure).
  const fetcher = useCallback(
    async (signal: AbortSignal) => {
      const result = await listTransferReviews({ page: page - 1 }, signal);
      return { result, checkedAt: Date.now() };
    },
    [page],
  );
  const { load, refreshing, refreshError, reload, retry } = useLoad(fetcher);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, focusHeading);

  return (
    <section aria-labelledby="transfer-queue-list-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          id="transfer-queue-list-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium focus:outline-none"
        >
          Held for review, page {page}
        </h2>
        {load.kind === 'loaded' && (
          <button
            type="button"
            onClick={reload}
            disabled={refreshing}
            aria-busy={refreshing}
            className={SECONDARY_BUTTON_CLASSES}
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        )}
      </div>
      {load.kind === 'loading' && (
        <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
          Loading transfers…
        </p>
      )}
      {load.kind === 'error' && (
        <LoadErrorNotice error={load.error} overrides={STAFF_ERROR_OVERRIDES}>
          <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
            Try again
          </button>
        </LoadErrorNotice>
      )}
      {load.kind === 'loaded' && (
        <>
          {refreshError && (
            <RefreshFailed
              error={refreshError.error}
              what="transfers"
              overrides={STAFF_ERROR_OVERRIDES}
            />
          )}
          <QueueTable
            items={load.data.result.items ?? []}
            checkedAt={load.data.checkedAt}
            page={page}
            onPage={onPage}
          />
          <Pager
            label="Transfer review pages"
            page={page}
            totalPages={load.data.result.page?.totalPages ?? 0}
            onPage={onPage}
          />
        </>
      )}
    </section>
  );
}

function QueueTable({
  items,
  checkedAt,
  page,
  onPage,
}: {
  items: TransferReviewSummary[];
  /** When the answer arrived (Date.now()), for the overdue marker. */
  checkedAt: number;
  page: number;
  onPage: (page: number) => void;
}) {
  if (items.length === 0) {
    return page > 1 ? (
      <div className="space-y-2">
        <p>There are no transfers on this page.</p>
        <button type="button" onClick={() => onPage(1)} className={SECONDARY_BUTTON_CLASSES}>
          Go to the first page
        </button>
      </div>
    ) : (
      <p>{EMPTY_TRANSFER_QUEUE_MESSAGE}</p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Transfers held for review, soonest deadline first</caption>
        <thead>
          <tr className="border-b border-slate-300 dark:border-slate-600">
            <th scope="col" className="py-2 pr-4 font-medium">
              Reference
            </th>
            <th scope="col" className="py-2 pr-4 text-right font-medium">
              Amount
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Held
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Review deadline
            </th>
            <th scope="col" className="py-2 pr-4 text-right font-medium">
              Score
            </th>
            <th scope="col" className="py-2 font-medium">
              <span className="sr-only">Details</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => (
            <tr
              key={item.transferId ?? index}
              className="border-b border-slate-200 align-top last:border-0 dark:border-slate-700"
            >
              <td className="py-2 pr-4 font-mono break-all">{item.reference ?? '—'}</td>
              <td className="py-2 pr-4 text-right whitespace-nowrap">
                {formatMoney(item.amount, item.currency)}
              </td>
              <td className="py-2 pr-4">
                <BangkokTime value={item.heldAt} />
              </td>
              <td className="py-2 pr-4">
                <BangkokTime value={item.reviewExpiresAt} />
                {isOverdue(item.reviewExpiresAt, checkedAt) && (
                  <span className="ml-2">
                    <StatusChip tone="danger">{OVERDUE_LABEL}</StatusChip>
                  </span>
                )}
              </td>
              <td className="py-2 pr-4 text-right">{item.score ?? '—'}</td>
              <td className="py-2">
                {item.transferId !== undefined && (
                  <Link
                    to={`/admin/transfer-reviews/${encodeURIComponent(item.transferId)}`}
                    className={LINK_CLASSES}
                  >
                    Review <span className="sr-only">transfer {item.reference ?? ''}</span>
                  </Link>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
